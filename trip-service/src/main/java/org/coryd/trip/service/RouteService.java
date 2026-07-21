package org.coryd.trip.service;

import lombok.RequiredArgsConstructor;
import org.coryd.common.enums.BookingStatus;
import org.coryd.common.enums.RouteStatus;
import org.coryd.common.exception.BadRequestException;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.common.kafka.KafkaTopics;
import org.coryd.common.kafka.events.BookingCancelledEvent;
import org.coryd.common.kafka.events.NotifyMeMatchedEvent;
import org.coryd.common.kafka.events.RouteCancelledEvent;
import org.coryd.common.kafka.events.RoutePublishedEvent;
import org.coryd.trip.config.MatchingProperties;
import org.coryd.trip.dto.PostRouteRequest;
import org.coryd.trip.dto.SearchRequest;
import org.coryd.trip.entity.Booking;
import org.coryd.trip.entity.DriverVerificationCache;
import org.coryd.trip.entity.FareReport;
import org.coryd.trip.entity.NotifyMeRequest;
import org.coryd.trip.entity.RoutePost;
import org.coryd.trip.entity.SuggestedFareRange;
import org.coryd.trip.repository.BookingRepository;
import org.coryd.trip.repository.DriverVerificationCacheRepository;
import org.coryd.trip.repository.FareReportRepository;
import org.coryd.trip.repository.NotifyMeRequestRepository;
import org.coryd.trip.repository.RoutePostRepository;
import org.coryd.trip.repository.SuggestedFareRangeRepository;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class RouteService {

    /** ~1 degree of latitude is ~111km; used to convert a meter tolerance to a lat/lng box. */
    private static final double METERS_PER_DEGREE = 111_000.0;

    private final RoutePostRepository routePostRepository;
    private final FareReportRepository fareReportRepository;
    private final SuggestedFareRangeRepository suggestedFareRangeRepository;
    private final NotifyMeRequestRepository notifyMeRequestRepository;
    private final BookingRepository bookingRepository;
    private final DriverVerificationCacheRepository driverVerificationCacheRepository;
    private final MatchingProperties matchingProperties;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Transactional
    public RoutePost post(String driverId, PostRouteRequest request) {
        boolean approved = driverVerificationCacheRepository.findById(driverId)
                .map(DriverVerificationCache::isApproved)
                .orElse(false);
        if (!approved) {
            throw new BadRequestException("Driver/vehicle is not yet verified - cannot post a route (D-104)");
        }

        RoutePost route = RoutePost.builder()
                .driverId(driverId)
                .vehicleId(request.vehicleId())
                .originLabel(request.originLabel())
                .originLat(request.originLat())
                .originLng(request.originLng())
                .destinationLabel(request.destinationLabel())
                .destinationLat(request.destinationLat())
                .destinationLng(request.destinationLng())
                .departureTime(request.departureTime())
                .seatCapacity(request.seatCapacity())
                .seatsAvailable(request.seatCapacity())
                .farePerSeat(request.farePerSeat())
                .status(RouteStatus.PUBLISHED)
                .build();
        routePostRepository.save(route);

        fareReportRepository.save(FareReport.builder()
                .corridorKey(corridorKey(request.originLabel(), request.destinationLabel()))
                .farePerSeat(request.farePerSeat())
                .build());

        kafkaTemplate.send(KafkaTopics.ROUTE_PUBLISHED, RoutePublishedEvent.builder()
                .routePostId(route.getId().toString())
                .driverId(driverId.toString())
                .originLabel(route.getOriginLabel())
                .destinationLabel(route.getDestinationLabel())
                .departureTime(LocalDateTime.ofInstant(route.getDepartureTime(), java.time.ZoneOffset.UTC))
                .seatsAvailable(route.getSeatsAvailable())
                .build());

        notifyMatchingWaitlist(route);

        return route;
    }

    private void notifyMatchingWaitlist(RoutePost route) {
        double latDelta = matchingProperties.getGeoToleranceMeters() / METERS_PER_DEGREE;
        double lngDelta = matchingProperties.getGeoToleranceMeters() / METERS_PER_DEGREE;
        Instant earliest = route.getDepartureTime().minus(matchingProperties.getTimeToleranceMinutes(), ChronoUnit.MINUTES);
        Instant latest = route.getDepartureTime().plus(matchingProperties.getTimeToleranceMinutes(), ChronoUnit.MINUTES);

        List<NotifyMeRequest> matches = notifyMeRequestRepository.findMatching(
                Instant.now(), earliest, latest,
                route.getOriginLat() - latDelta, route.getOriginLat() + latDelta,
                route.getOriginLng() - lngDelta, route.getOriginLng() + lngDelta,
                route.getDestinationLat() - latDelta, route.getDestinationLat() + latDelta,
                route.getDestinationLng() - lngDelta, route.getDestinationLng() + lngDelta
        );

        for (NotifyMeRequest request : matches) {
            request.setFulfilled(true);
            notifyMeRequestRepository.save(request);
            kafkaTemplate.send(KafkaTopics.NOTIFY_ME_MATCHED, NotifyMeMatchedEvent.builder()
                    .notifyMeRequestId(request.getId().toString())
                    .riderId(request.getRiderId().toString())
                    .routePostId(route.getId().toString())
                    .build());
        }
    }

    public List<RoutePost> search(SearchRequest request) {
        double latDelta = matchingProperties.getGeoToleranceMeters() / METERS_PER_DEGREE;
        double lngDelta = matchingProperties.getGeoToleranceMeters() / METERS_PER_DEGREE;
        Instant earliest = request.desiredDepartureTime().minus(matchingProperties.getTimeToleranceMinutes(), ChronoUnit.MINUTES);
        Instant latest = request.desiredDepartureTime().plus(matchingProperties.getTimeToleranceMinutes(), ChronoUnit.MINUTES);

        return routePostRepository.search(
                earliest, latest,
                request.originLat() - latDelta, request.originLat() + latDelta,
                request.originLng() - lngDelta, request.originLng() + lngDelta,
                request.destinationLat() - latDelta, request.destinationLat() + latDelta,
                request.destinationLng() - lngDelta, request.destinationLng() + lngDelta
        );
    }

    public Optional<SuggestedFareRange> suggestedFare(String originLabel, String destinationLabel) {
        return suggestedFareRangeRepository.findByCorridorKey(corridorKey(originLabel, destinationLabel));
    }

    @Transactional
    public NotifyMeRequest requestNotification(String riderId, SearchRequest request) {
        return notifyMeRequestRepository.save(NotifyMeRequest.builder()
                .riderId(riderId)
                .originLat(request.originLat())
                .originLng(request.originLng())
                .destinationLat(request.destinationLat())
                .destinationLng(request.destinationLng())
                .desiredDepartureTime(request.desiredDepartureTime())
                .expiresAt(Instant.now().plus(24, ChronoUnit.HOURS))
                .build());
    }

    @Transactional
    public void cancel(String routePostId) {
        RoutePost route = routePostRepository.findById(routePostId)
                .orElseThrow(() -> new ResourceNotFoundException("Route post not found"));

        route.setStatus(RouteStatus.CANCELLED);
        routePostRepository.save(route);

        List<Booking> affected = bookingRepository.findByRoutePostId(routePostId);
        List<String> affectedIds = affected.stream().map(b -> b.getId().toString()).toList();

        for (Booking booking : affected) {
            if (booking.getStatus() == BookingStatus.HELD || booking.getStatus() == BookingStatus.CONFIRMED) {
                booking.setStatus(BookingStatus.CANCELLED_DRIVER);
                bookingRepository.save(booking);
                kafkaTemplate.send(KafkaTopics.BOOKING_CANCELLED, BookingCancelledEvent.builder()
                        .bookingId(booking.getId().toString())
                        .routePostId(routePostId.toString())
                        .riderId(booking.getRiderId().toString())
                        .cancelledBy("DRIVER")
                        .feeApplied(false)
                        .cancelledAt(LocalDateTime.now())
                        .build());
            }
        }

        kafkaTemplate.send(KafkaTopics.ROUTE_CANCELLED, RouteCancelledEvent.builder()
                .routePostId(routePostId.toString())
                .driverId(route.getDriverId().toString())
                .affectedBookingIds(affectedIds)
                .cancelledAt(LocalDateTime.now())
                .build());
    }

    private String corridorKey(String originLabel, String destinationLabel) {
        return (originLabel + "->" + destinationLabel).toLowerCase();
    }
}
