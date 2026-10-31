package org.coryd.trip.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.enums.RouteStatus;
import org.coryd.common.enums.SavedPlaceType;
import org.coryd.common.kafka.KafkaTopics;
import org.coryd.common.kafka.events.DriverNearbyDemandEvent;
import org.coryd.trip.config.DemandProperties;
import org.coryd.trip.config.NearbyAlertProperties;
import org.coryd.trip.entity.DriverPresence;
import org.coryd.trip.entity.RoutePost;
import org.coryd.trip.entity.RouteTemplate;
import org.coryd.trip.repository.DriverPresenceRepository;
import org.coryd.trip.repository.RoutePostRepository;
import org.coryd.trip.repository.RouteTemplateRepository;
import org.coryd.trip.repository.SavedPlaceRepository;
import org.coryd.trip.repository.SuspendedUserRepository;
import org.coryd.trip.util.GeoMath;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * "4 people near you are going to Ajah. Leaving now?" - to a driver who opened the app near riders who are waiting,
 * when those riders are going where the driver goes: their Home, a regular route, or a trip they've driven lately.
 * Only ever about the driver's own way - a driver is never asked to go somewhere else.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NearbyAlertService {

    /** Destinations closer than this are one place. */
    private static final double SAME_PLACE_KM = 0.3;
    private static final int MAX_DESTINATIONS = 10;

    private final DriverPresenceRepository driverPresenceRepository;
    private final NearbyDemandService nearbyDemandService;
    private final SavedPlaceRepository savedPlaceRepository;
    private final RouteTemplateRepository routeTemplateRepository;
    private final RoutePostRepository routePostRepository;
    private final SuspendedUserRepository suspendedUserRepository;
    private final RouteService routeService;
    private final NearbyAlertProperties properties;
    private final DemandProperties demandProperties;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    /** A place a driver goes, and what it's called. */
    record Destination(String label, double lat, double lng) {
    }

    public void recordLocation(String driverId, double lat, double lng) {
        driverPresenceRepository.record(driverId, lat, lng, Instant.now());
    }

    public void forgetLocation(String driverId) {
        driverPresenceRepository.forget(driverId);
    }

    /** One pass - each driver is checked on their own, so one failure never stops the rest. Returns pushes sent. */
    public int sendAlerts() {
        if (!properties.isEnabled()) {
            return 0;
        }
        Instant now = Instant.now();
        LocalDate today = GeoMath.lagos(now).toLocalDate();
        Instant cooldownBefore = now.minus(Duration.ofMinutes(properties.getCooldownMinutes()));
        int sent = 0;
        for (DriverPresence presence : driverPresenceRepository.findAlertable(now.minus(Duration.ofMinutes(properties.getPresenceMinutes())),
                cooldownBefore, today, properties.getMaxPerDay())) {
            try {
                if (alert(presence, now, today, cooldownBefore)) {
                    sent++;
                }
            } catch (Exception e) {
                log.warn("Could not check riders near driver {}: {}", presence.getDriverId(), e.getMessage());
            }
        }
        return sent;
    }

    /** Locations past their life are cleared; rows nobody needs any more are removed. */
    public void clearStale() {
        Instant now = Instant.now();
        driverPresenceRepository.clearStale(now.minus(Duration.ofMinutes(properties.getPresenceMinutes())));
        driverPresenceRepository.deleteIdle(now.minus(Duration.ofDays(1)));
    }

    private boolean alert(DriverPresence presence, Instant now, LocalDate today, Instant cooldownBefore) {
        String driverId = presence.getDriverId();
        if (suspendedUserRepository.existsById(driverId) || hasTripUnderway(driverId, now)) {
            return false;
        }
        List<Destination> destinations = destinationsOf(driverId, now);
        if (destinations.isEmpty()) {
            return false;
        }
        List<NearbyDemandService.WaitingRider> waiting = nearbyDemandService.waitingNear(presence.getLat(), presence.getLng(),
                properties.getRadiusMeters(), driverId);
        if (waiting.size() < demandProperties.getNearbyMinRiders()) {
            return false;
        }

        Destination best = null;
        List<NearbyDemandService.WaitingRider> bestRiders = List.of();
        for (Destination destination : destinations) {
            List<NearbyDemandService.WaitingRider> going = waiting.stream()
                    .filter(r -> nearbyDemandService.headingTowards(presence.getLat(), presence.getLng(), destination.lat(), destination.lng(), r))
                    .toList();
            if (going.size() > bestRiders.size()) {
                best = destination;
                bestRiders = going;
            }
        }
        if (best == null || bestRiders.size() < demandProperties.getNearbyMinRiders() || !canPost(driverId)) {
            return false;
        }
        if (driverPresenceRepository.claimAlert(driverId, now, cooldownBefore, today, properties.getMaxPerDay()) == 0) {
            return false;
        }
        String label = nearbyDemandService.groupByDestination(bestRiders, 1).stream()
                .map(g -> g.areaLabel()).filter(Objects::nonNull).findFirst().orElse(best.label());
        kafkaTemplate.send(KafkaTopics.DRIVER_NEARBY_DEMAND, DriverNearbyDemandEvent.builder()
                .driverId(driverId)
                .destinationLabel(label)
                .riderCount(bestRiders.size())
                .build());
        log.info("Told driver {} that {} rider(s) near them are going towards {}", driverId, bestRiders.size(), label);
        return true;
    }

    /** Home, regular routes, then trips driven lately - each place once. */
    List<Destination> destinationsOf(String driverId, Instant now) {
        List<Destination> all = new ArrayList<>();
        savedPlaceRepository.findFirstByUserIdAndType(driverId, SavedPlaceType.HOME)
                .ifPresent(h -> all.add(new Destination(h.getLabel(), h.getLat(), h.getLng())));
        for (RouteTemplate t : routeTemplateRepository.findByDriverId(driverId)) {
            if (!t.isPaused()) {
                all.add(new Destination(t.getDestinationLabel(), t.getDestinationLat(), t.getDestinationLng()));
            }
        }
        Instant since = now.minus(Duration.ofDays(demandProperties.getDriverHistoryDays()));
        for (RoutePost r : routePostRepository.findTop50ByDriverIdAndStatusNotOrderByCreatedAtDesc(driverId, RouteStatus.CANCELLED)) {
            if (r.getCreatedAt() != null && r.getCreatedAt().isAfter(since)) {
                all.add(new Destination(r.getDestinationLabel(), r.getDestinationLat(), r.getDestinationLng()));
            }
        }
        List<Destination> distinct = new ArrayList<>();
        for (Destination d : all) {
            if (distinct.stream().noneMatch(x -> GeoMath.distanceKm(x.lat(), x.lng(), d.lat(), d.lng()) < SAME_PLACE_KM)) {
                distinct.add(d);
                if (distinct.size() == MAX_DESTINATIONS) {
                    break;
                }
            }
        }
        return distinct;
    }

    /** Already posted a trip leaving about now, or driving one - the passenger screen tells them instead. */
    private boolean hasTripUnderway(String driverId, Instant now) {
        return routePostRepository.findByDriverIdAndStatusIn(driverId, TripScheduleService.LIVE_ROUTE_STATUSES).stream()
                .anyMatch(r -> TripScheduleService.isActive(r, now));
    }

    private boolean canPost(String driverId) {
        try {
            return routeService.postingEligibility(driverId).canPostRoutes();
        } catch (Exception e) {
            log.warn("Could not check whether driver {} can post: {}", driverId, e.getMessage());
            return false;
        }
    }
}
