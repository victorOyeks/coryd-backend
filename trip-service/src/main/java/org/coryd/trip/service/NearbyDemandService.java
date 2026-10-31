package org.coryd.trip.service;

import lombok.RequiredArgsConstructor;
import org.coryd.common.enums.BookingStatus;
import org.coryd.common.exception.BadRequestException;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.common.exception.UnauthorizedException;
import org.coryd.trip.config.DemandProperties;
import org.coryd.trip.config.MatchingProperties;
import org.coryd.trip.dto.NearbyDemandItem;
import org.coryd.trip.entity.NotifyMeRequest;
import org.coryd.trip.entity.RoutePost;
import org.coryd.trip.entity.RouteSearch;
import org.coryd.trip.repository.BookingRepository;
import org.coryd.trip.repository.NotifyMeRequestRepository;
import org.coryd.trip.repository.RoutePostRepository;
import org.coryd.trip.repository.RouteSearchRepository;
import org.coryd.trip.util.GeoMath;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Riders waiting near a spot right now - "3 others near you are also going to Ajah", "4 people near you are going
 * your way". A rider is waiting when they have a one-off "notify me" for about now, or searched from nearby in the
 * last few minutes for about now, and don't already have a seat. Only ever counts per destination area, from
 * DemandProperties.nearbyMinRiders up - never who, and never an exact point.
 */
@Service
@RequiredArgsConstructor
public class NearbyDemandService {

    /** Holding or riding a seat - already sorted, so not waiting. */
    private static final List<BookingStatus> HAS_A_SEAT = List.of(BookingStatus.HELD, BookingStatus.RESERVED, BookingStatus.CONFIRMED,
            BookingStatus.EN_ROUTE_TO_PICKUP, BookingStatus.AT_PICKUP, BookingStatus.IN_PROGRESS);

    private final NotifyMeRequestRepository notifyMeRequestRepository;
    private final RouteSearchRepository routeSearchRepository;
    private final BookingRepository bookingRepository;
    private final RoutePostRepository routePostRepository;
    private final RouteMatcher routeMatcher;
    private final UserBlockService userBlockService;
    private final DemandProperties properties;
    private final MatchingProperties matchingProperties;

    /** One waiting rider: where they want to go. Never leaves this service with an id attached. */
    public record WaitingRider(String riderId, double originLat, double originLng, double destinationLat, double destinationLng,
                               String destinationLabel, Instant since) {
    }

    /**
     * GET /routes/demand/nearby. routePostId (the caller's own trip) counts riders that trip can carry; a
     * destination counts riders on the way from here to there; neither counts everyone waiting nearby.
     */
    @Transactional(readOnly = true)
    public List<NearbyDemandItem> nearby(String callerId, double lat, double lng, Integer radiusMeters,
                                         String routePostId, Double destinationLat, Double destinationLng) {
        if ((destinationLat == null) != (destinationLng == null)) {
            throw new BadRequestException("Send both destinationLat and destinationLng, or neither");
        }
        if (routePostId != null && destinationLat != null) {
            throw new BadRequestException("Filter by routePostId or by destination, not both");
        }
        int radius = radiusMeters == null ? properties.getNearbyDefaultRadiusMeters()
                : Math.max(50, Math.min(radiusMeters, properties.getNearbyMaxRadiusMeters()));

        Predicate<WaitingRider> goingMyWay = r -> true;
        if (routePostId != null) {
            RoutePost route = routePostRepository.findById(routePostId)
                    .orElseThrow(() -> new ResourceNotFoundException("Route post not found"));
            if (!route.getDriverId().equals(callerId)) {
                throw new UnauthorizedException("Only this route's driver can see who is waiting along it");
            }
            goingMyWay = r -> canCarry(route, r);
        } else if (destinationLat != null) {
            goingMyWay = r -> headingTowards(lat, lng, destinationLat, destinationLng, r);
        }

        List<WaitingRider> waiting = waitingNear(lat, lng, radius, callerId).stream().filter(goingMyWay).toList();
        return groupByDestination(waiting, properties.getNearbyMinRiders());
    }

    /** Everyone waiting within radiusMeters of the point, one entry per rider, without `excludeUserId` and anyone kept apart from them. */
    @Transactional(readOnly = true)
    public List<WaitingRider> waitingNear(double lat, double lng, int radiusMeters, String excludeUserId) {
        Instant now = Instant.now();
        double km = radiusMeters / 1000.0;
        double dLat = GeoMath.latDegrees(km);
        double dLng = GeoMath.lngDegrees(km, lat);
        Duration window = Duration.ofMinutes(properties.getNearbyDepartureWindowMinutes());

        Map<String, WaitingRider> byRider = new HashMap<>();
        for (NotifyMeRequest n : notifyMeRequestRepository.findWaitingOneOffsStartingIn(now, now.minus(window), now.plus(window),
                lat - dLat, lat + dLat, lng - dLng, lng + dLng)) {
            keepNewest(byRider, new WaitingRider(n.getRiderId(), n.getOriginLat(), n.getOriginLng(),
                    n.getDestinationLat(), n.getDestinationLng(), n.getDestinationLabel(), n.getCreatedAt()));
        }
        for (RouteSearch s : routeSearchRepository.findRecentStartingIn(now.minus(Duration.ofMinutes(properties.getNearbySearchMinutes())),
                now.minus(window), now.plus(window), lat - dLat, lat + dLat, lng - dLng, lng + dLng)) {
            keepNewest(byRider, new WaitingRider(s.getRiderId(), s.getOriginLat(), s.getOriginLng(),
                    s.getDestinationLat(), s.getDestinationLng(), s.getDestinationLabel(), s.getCreatedAt()));
        }

        byRider.values().removeIf(r -> GeoMath.distanceKm(lat, lng, r.originLat(), r.originLng()) > km);
        if (excludeUserId != null) {
            byRider.remove(excludeUserId);
            userBlockService.keptApartFrom(excludeUserId).forEach(byRider::remove);
        }
        if (!byRider.isEmpty()) {
            bookingRepository.ridersWithASeatBetween(byRider.keySet(), HAS_A_SEAT,
                    now.minus(Duration.ofHours(2)), now.plus(Duration.ofHours(3))).forEach(byRider::remove);
        }
        return new ArrayList<>(byRider.values());
    }

    /** Waiting riders near a trip's start that the trip can carry - its driver's "4 people near you are going your way". */
    @Transactional(readOnly = true)
    public int waitingAlong(RoutePost route) {
        return (int) waitingNear(route.getOriginLat(), route.getOriginLng(), matchingProperties.getWalkMeters(), route.getDriverId())
                .stream().filter(r -> canCarry(route, r)).count();
    }

    /** Groups riders by the area they're going to - smallest groups hidden, biggest first. */
    public List<NearbyDemandItem> groupByDestination(Collection<WaitingRider> riders, int minRiders) {
        double cell = properties.getCellDegrees();
        Map<String, List<WaitingRider>> groups = riders.stream().collect(Collectors.groupingBy(
                r -> GeoMath.cell(r.destinationLat(), r.destinationLng(), cell), LinkedHashMap::new, Collectors.toList()));
        return groups.entrySet().stream()
                .filter(e -> e.getValue().size() >= Math.max(1, minRiders))
                .map(e -> {
                    String[] parts = e.getKey().split(":");
                    // The middle of the area, never a rider's own point.
                    double centreLat = (Long.parseLong(parts[0]) + 0.5) * cell;
                    double centreLng = (Long.parseLong(parts[1]) + 0.5) * cell;
                    return new NearbyDemandItem(commonestLabel(e.getValue()), centreLat, centreLng, e.getValue().size());
                })
                .sorted(Comparator.comparingInt(NearbyDemandItem::waitingCount).reversed())
                .toList();
    }

    /** The trip passes both of the rider's ends in order, or starts and ends near theirs. */
    boolean canCarry(RoutePost route, WaitingRider r) {
        double endsKm = matchingProperties.getGeoToleranceMeters() / 1000.0;
        boolean endToEnd = GeoMath.distanceKm(route.getOriginLat(), route.getOriginLng(), r.originLat(), r.originLng()) <= endsKm
                && GeoMath.distanceKm(route.getDestinationLat(), route.getDestinationLng(), r.destinationLat(), r.destinationLng()) <= endsKm;
        return endToEnd || routeMatcher.enabled()
                && routeMatcher.match(route, r.originLat(), r.originLng(), r.destinationLat(), r.destinationLng()).isPresent();
    }

    /** The rider's destination is near the driver's, or on the way there from here. */
    boolean headingTowards(double fromLat, double fromLng, double toLat, double toLng, WaitingRider r) {
        double corridor = properties.getNearbyCorridorKm();
        return GeoMath.distanceKm(toLat, toLng, r.destinationLat(), r.destinationLng()) <= corridor
                || GeoMath.onTheWay(fromLat, fromLng, toLat, toLng, r.destinationLat(), r.destinationLng(), corridor);
    }

    private static void keepNewest(Map<String, WaitingRider> byRider, WaitingRider candidate) {
        byRider.merge(candidate.riderId(), candidate, (a, b) ->
                Comparator.comparing(WaitingRider::since, Comparator.nullsFirst(Comparator.naturalOrder())).compare(a, b) >= 0 ? a : b);
    }

    private static String commonestLabel(List<WaitingRider> riders) {
        return riders.stream().map(WaitingRider::destinationLabel).filter(Objects::nonNull).filter(l -> !l.isBlank())
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()))
                .entrySet().stream()
                .max(Map.Entry.<String, Long>comparingByValue().thenComparing(Map.Entry.comparingByKey(Comparator.reverseOrder())))
                .map(Map.Entry::getKey)
                .orElse(null);
    }
}
