package org.coryd.trip.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.enums.BookingStatus;
import org.coryd.common.enums.RouteStatus;
import org.coryd.common.exception.BadRequestException;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.common.exception.UnauthorizedException;
import org.coryd.common.kafka.KafkaTopics;
import org.coryd.common.kafka.events.TripSafetyCheckEvent;
import org.coryd.common.kafka.events.TripSafetyEscalatedEvent;
import org.coryd.trip.client.IdentityServiceClient;
import org.coryd.trip.client.ProfileServiceClient;
import org.coryd.trip.config.TripSafetyProperties;
import org.coryd.trip.entity.Booking;
import org.coryd.trip.entity.RoutePost;
import org.coryd.trip.entity.TripSafetyAlert;
import org.coryd.trip.event.DriverLocationRecordedEvent;
import org.coryd.trip.repository.BookingRepository;
import org.coryd.trip.repository.RoutePostRepository;
import org.coryd.trip.repository.TripSafetyAlertRepository;
import org.coryd.trip.util.GeoMath;
import org.coryd.trip.util.PolylineDecoder;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Watches trips in progress from the driver's location updates. A long stop away from any pickup, a
 * long way off the planned road, or the driver's phone going quiet each asks everyone on board "Are you
 * OK?". "I need help" raises an SOS straight away; silence past the answer window alerts ops.
 *
 * <p>State between pings lives in Redis, like the reroute check it sits beside; alerts live in Postgres.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TripSafetyService {

    static final String STOP_KEY = "trip:safety:stop:";
    static final String STOP_FLAGGED_KEY = "trip:safety:stop:flagged:";
    static final String OFF_ROUTE_KEY = "trip:safety:offroute:";
    static final String LAST_SEEN_KEY = "trip:safety:lastseen:";
    /** Set while a signal-lost check is open, so the next location ping can close it without a query on every ping. */
    static final String SIGNAL_LOST_OPEN_KEY = "trip:safety:signallost:open:";
    private static final Duration STATE_TTL = Duration.ofHours(12);
    /** Riders on board or about to be - everyone a check-in is for. */
    private static final List<BookingStatus> ON_TRIP = TripScheduleService.SEATED_STATUSES;

    private final TripSafetyProperties properties;
    private final TripSafetyAlertRepository alertRepository;
    private final RoutePostRepository routePostRepository;
    private final BookingRepository bookingRepository;
    private final BookingService bookingService;
    private final IdentityServiceClient identityServiceClient;
    private final ProfileServiceClient profileServiceClient;
    private final StringRedisTemplate redisTemplate;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    /** Every driver location during a trip. Off the request thread, and never throws - a safety check must not break tracking. */
    @Async("routeDeviationExecutor")
    @EventListener
    public void onLocation(DriverLocationRecordedEvent event) {
        onLocation(event.routePostId(), event.lat(), event.lng());
    }

    void onLocation(String routePostId, double lat, double lng) {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            RoutePost route = routePostRepository.findById(routePostId).orElse(null);
            Instant now = Instant.now();
            if (route == null || route.getStatus() != RouteStatus.IN_PROGRESS || !watching(route, now)) {
                return;
            }
            redisTemplate.opsForValue().set(LAST_SEEN_KEY + routePostId, String.valueOf(now.toEpochMilli()), STATE_TTL);
            if (Boolean.TRUE.equals(redisTemplate.delete(SIGNAL_LOST_OPEN_KEY + routePostId))) {
                signalBack(routePostId);
            }
            checkStop(route, lat, lng, now);
            checkOffRoute(route, lat, lng, now);
        } catch (Exception e) {
            log.warn("Safety check failed for route {}: {}", routePostId, e.getMessage());
        }
    }

    private void checkStop(RoutePost route, double lat, double lng, Instant now) {
        String key = STOP_KEY + route.getId();
        String anchor = redisTemplate.opsForValue().get(key);
        if (anchor == null) {
            redisTemplate.opsForValue().set(key, lat + "," + lng + "," + now.toEpochMilli(), STATE_TTL);
            return;
        }
        String[] parts = anchor.split(",");
        double aLat = Double.parseDouble(parts[0]);
        double aLng = Double.parseDouble(parts[1]);
        if (GeoMath.distanceKm(aLat, aLng, lat, lng) * 1000 > properties.getStopRadiusMeters()) {
            redisTemplate.opsForValue().set(key, lat + "," + lng + "," + now.toEpochMilli(), STATE_TTL);
            return;
        }
        Instant since = Instant.ofEpochMilli(Long.parseLong(parts[2]));
        if (Duration.between(since, now).toMinutes() < properties.getStopMinutes() || nearKnownStop(route, lat, lng)) {
            return;
        }
        // Once per stop: the flag carries the stop's start, so a new stop can be flagged again.
        Boolean first = redisTemplate.opsForValue().setIfAbsent(STOP_FLAGGED_KEY + route.getId() + ":" + parts[2], "1", STATE_TTL);
        if (Boolean.TRUE.equals(first)) {
            raise(route, TripSafetyAlert.Kind.STOPPED, lat, lng);
        }
    }

    private void checkOffRoute(RoutePost route, double lat, double lng, Instant now) {
        String baseline = redisTemplate.opsForValue().get(RouteDeviationService.BASELINE_KEY_PREFIX + route.getId());
        if (baseline == null) {
            return;
        }
        List<PolylineDecoder.LatLng> points = PolylineDecoder.decode(baseline);
        if (points.size() < 2) {
            return;
        }
        String key = OFF_ROUTE_KEY + route.getId();
        if (RouteDeviationService.distanceToPolylineMeters(lat, lng, points) <= properties.getOffRouteMeters()) {
            redisTemplate.delete(key);
            return;
        }
        String since = redisTemplate.opsForValue().get(key);
        if (since == null) {
            redisTemplate.opsForValue().set(key, String.valueOf(now.toEpochMilli()), STATE_TTL);
            return;
        }
        if ("flagged".equals(since)) {
            return;
        }
        if (Duration.between(Instant.ofEpochMilli(Long.parseLong(since)), now).toMinutes() >= properties.getOffRouteMinutes()) {
            redisTemplate.opsForValue().set(key, "flagged", STATE_TTL);
            raise(route, TripSafetyAlert.Kind.OFF_ROUTE, lat, lng);
        }
    }

    /** Waiting at the start, the destination, a pickup or a drop-off is the job, not a warning sign. */
    private boolean nearKnownStop(RoutePost route, double lat, double lng) {
        double radiusKm = properties.getKnownStopRadiusMeters() / 1000.0;
        if (GeoMath.distanceKm(lat, lng, route.getOriginLat(), route.getOriginLng()) <= radiusKm
                || GeoMath.distanceKm(lat, lng, route.getDestinationLat(), route.getDestinationLng()) <= radiusKm) {
            return true;
        }
        return bookingRepository.findByRoutePostId(route.getId()).stream()
                .anyMatch(b -> near(lat, lng, b.getPickupLat(), b.getPickupLng(), radiusKm)
                        || near(lat, lng, b.getDropoffLat(), b.getDropoffLng(), radiusKm));
    }

    private static boolean near(double lat, double lng, Double otherLat, Double otherLng, double radiusKm) {
        return otherLat != null && otherLng != null && GeoMath.distanceKm(lat, lng, otherLat, otherLng) <= radiusKm;
    }

    /** Trips whose driver's phone has gone quiet - run every minute. */
    public void checkSignalLost() {
        if (!properties.isEnabled()) {
            return;
        }
        Instant now = Instant.now();
        for (RoutePost route : routePostRepository.findByStatus(RouteStatus.IN_PROGRESS)) {
            if (!watching(route, now)) {
                continue;
            }
            String seen = redisTemplate.opsForValue().get(LAST_SEEN_KEY + route.getId());
            Instant lastSeen = seen != null ? Instant.ofEpochMilli(Long.parseLong(seen)) : route.getStartedAt();
            if (lastSeen == null || Duration.between(lastSeen, now).toMinutes() < properties.getSignalLostMinutes()) {
                continue;
            }
            // Once per silence: keyed on when it went quiet.
            Boolean first = redisTemplate.opsForValue().setIfAbsent(
                    LAST_SEEN_KEY + route.getId() + ":flagged:" + lastSeen.toEpochMilli(), "1", STATE_TTL);
            if (Boolean.TRUE.equals(first)) {
                raise(route, TripSafetyAlert.Kind.SIGNAL_LOST, null, null);
                redisTemplate.opsForValue().set(SIGNAL_LOST_OPEN_KEY + route.getId(), "1", STATE_TTL);
            }
        }
    }

    /**
     * Only until the trip should have ended: start (or departure) + its estimated duration + the grace period.
     * A trip still "in progress" long after that was never completed in the app - nobody is in danger, and the
     * stuck-trip job already flags it for ops. Without this, one forgotten trip asks "Are you OK?" forever.
     */
    boolean watching(RoutePost route, Instant now) {
        Instant start = route.getStartedAt() != null ? route.getStartedAt() : route.getDepartureTime();
        if (start == null) {
            return true;
        }
        int expectedMinutes = route.getEstimatedDurationMinutes() != null ? route.getEstimatedDurationMinutes() : 120;
        return now.isBefore(start.plus(Duration.ofMinutes(expectedMinutes + (long) properties.getWatchGraceMinutes())));
    }

    @Transactional
    public void raise(RoutePost route, TripSafetyAlert.Kind kind, Double lat, Double lng) {
        if (alertRepository.existsByRoutePostIdAndKindAndStatus(route.getId(), kind, TripSafetyAlert.Status.OPEN)) {
            return;
        }
        // One of each kind per trip per repeat window - a driver whose phone keeps dropping out is one alert, not ten.
        if (alertRepository.existsByRoutePostIdAndKindAndCreatedAtAfter(route.getId(), kind,
                Instant.now().minus(Duration.ofMinutes(properties.getRepeatMinutes())))) {
            log.info("Trip safety {} on route {} already raised in the last {} min - not asking again", kind, route.getId(),
                    properties.getRepeatMinutes());
            return;
        }
        TripSafetyAlert alert = TripSafetyAlert.builder()
                .routePostId(route.getId()).kind(kind).latitude(lat).longitude(lng).build();
        List<String> onBoard = new ArrayList<>();
        onBoard.add(route.getDriverId());
        ridersOnTrip(route.getId()).forEach(b -> onBoard.add(b.getRiderId()));
        onBoard.stream().distinct().forEach(id -> alert.getAnswers().put(id, TripSafetyAlert.Answer.WAITING));
        alertRepository.save(alert);
        log.info("Trip safety alert {} on route {}: {}", alert.getId(), route.getId(), kind);

        kafkaTemplate.send(KafkaTopics.TRIP_SAFETY_CHECK, TripSafetyCheckEvent.builder()
                .alertId(alert.getId()).routePostId(route.getId()).kind(kind.name())
                .userIds(onBoard.stream().distinct().toList()).build());
    }

    /** "I'm fine" or "I need help" - help raises an SOS for whoever asked, from wherever they are. */
    @Transactional
    public TripSafetyAlert respond(String alertId, String callerId, boolean ok, Double lat, Double lng) {
        TripSafetyAlert alert = alertRepository.findById(alertId)
                .orElseThrow(() -> new ResourceNotFoundException("Safety check not found"));
        if (!alert.getAnswers().containsKey(callerId)) {
            throw new UnauthorizedException("This safety check isn't for you");
        }
        alert.getAnswers().put(callerId, ok ? TripSafetyAlert.Answer.FINE : TripSafetyAlert.Answer.HELP);
        if (!ok) {
            alert.setStatus(TripSafetyAlert.Status.HELP);
            RoutePost route = routePostRepository.findById(alert.getRoutePostId())
                    .orElseThrow(() -> new ResourceNotFoundException("Route post not found"));
            if (callerId.equals(route.getDriverId())) {
                bookingService.triggerRouteSos(route.getId(), callerId, lat, lng);
            } else {
                Booking booking = ridersOnTrip(route.getId()).stream().filter(b -> callerId.equals(b.getRiderId())).findFirst()
                        .orElseThrow(() -> new BadRequestException("You're no longer on this trip"));
                bookingService.triggerSos(booking.getId(), callerId, lat, lng);
            }
        } else if (alert.getStatus() == TripSafetyAlert.Status.OPEN
                && alert.getAnswers().values().stream().allMatch(TripSafetyAlert.Answer.FINE::equals)) {
            alert.setStatus(TripSafetyAlert.Status.ALL_OK);
        }
        return alertRepository.save(alert);
    }

    /** The driver's phone is sending again - its open signal-lost check is answered by that. */
    @Transactional
    public void signalBack(String routePostId) {
        for (TripSafetyAlert alert : alertRepository.findByRoutePostIdAndKindAndStatus(routePostId, TripSafetyAlert.Kind.SIGNAL_LOST,
                TripSafetyAlert.Status.OPEN)) {
            alert.setStatus(TripSafetyAlert.Status.CLOSED);
            alertRepository.save(alert);
            log.info("Trip safety alert {} on route {} closed - the driver's phone is sending its location again", alert.getId(), routePostId);
        }
    }

    /**
     * Still open past the answer window - run every minute. Only a trip still in progress is escalated: a check on a trip
     * that has since been completed or cancelled is closed instead (on 22 Nov one escalated 90 seconds after the trip was
     * completed, and ops was re-alerted for half an hour about riders who were home). A signal-lost check on its own also
     * waits until the trip is overdue - see {@link TripSafetyProperties#getSignalLostOverdueMinutes()}.
     */
    @Transactional
    public void escalateUnanswered() {
        Instant now = Instant.now();
        Instant cutoff = now.minus(Duration.ofMinutes(properties.getAnswerMinutes()));
        for (TripSafetyAlert alert : alertRepository.findByStatusAndCreatedAtBefore(TripSafetyAlert.Status.OPEN, cutoff)) {
            RoutePost route = routePostRepository.findById(alert.getRoutePostId()).orElse(null);
            if (route == null || route.getStatus() != RouteStatus.IN_PROGRESS) {
                alert.setStatus(TripSafetyAlert.Status.CLOSED);
                alertRepository.save(alert);
                redisTemplate.delete(SIGNAL_LOST_OPEN_KEY + alert.getRoutePostId());
                log.info("Trip safety alert {} closed unanswered - route {} is no longer in progress ({})", alert.getId(),
                        alert.getRoutePostId(), route == null ? "gone" : route.getStatus());
                continue;
            }
            if (alert.getKind() == TripSafetyAlert.Kind.SIGNAL_LOST && now.isBefore(overdueAt(route))) {
                continue;
            }
            alert.setStatus(TripSafetyAlert.Status.ESCALATED);
            alert.setEscalatedAt(now);
            alertRepository.save(alert);
            escalate(alert, route);
        }
    }

    /** When a quiet phone becomes a worry: expected arrival (start + estimated duration) plus the overdue allowance. */
    Instant overdueAt(RoutePost route) {
        Instant start = route.getStartedAt() != null ? route.getStartedAt() : route.getDepartureTime();
        if (start == null) {
            start = route.getCreatedAt() != null ? route.getCreatedAt() : Instant.now();
        }
        int expectedMinutes = route.getEstimatedDurationMinutes() != null ? route.getEstimatedDurationMinutes() : 120;
        return start.plus(Duration.ofMinutes(expectedMinutes + (long) properties.getSignalLostOverdueMinutes()));
    }

    private void escalate(TripSafetyAlert alert, RoutePost route) {
        String riderPhones = ridersOnTrip(route.getId()).stream()
                .map(b -> identityServiceClient.fetchPhone(b.getRiderId())).filter(Objects::nonNull)
                .collect(Collectors.joining(", "));
        String driverName = profileServiceClient.fetchDriverName(route.getDriverId());
        String driverPhone = identityServiceClient.fetchPhone(route.getDriverId());
        kafkaTemplate.send(KafkaTopics.TRIP_SAFETY_ESCALATED, TripSafetyEscalatedEvent.builder()
                .alertId(alert.getId())
                .routePostId(route.getId())
                .kind(alert.getKind().name())
                .routeSummary(route.getOriginLabel() + " → " + route.getDestinationLabel())
                .driverName(driverName)
                .driverPhone(driverPhone)
                .vehiclePlate(route.getVehicleId() == null ? null : profileServiceClient.fetchVehiclePlate(route.getVehicleId()))
                .riderPhones(riderPhones.isEmpty() ? null : riderPhones)
                .latitude(alert.getLatitude())
                .longitude(alert.getLongitude())
                .unanswered((int) alert.getAnswers().values().stream().filter(TripSafetyAlert.Answer.WAITING::equals).count())
                .notAnswered(people(alert, route, TripSafetyAlert.Answer.WAITING, driverName, driverPhone))
                .answeredOk(people(alert, route, TripSafetyAlert.Answer.FINE, driverName, driverPhone))
                .build());
        log.warn("Trip safety alert {} on route {} unanswered - ops alerted", alert.getId(), route.getId());
    }

    /** Everyone whose answer is {@code answer}, by role - "driver Kunle Adeyemi 0800..., rider 0803..." - or null for nobody. */
    private String people(TripSafetyAlert alert, RoutePost route, TripSafetyAlert.Answer answer, String driverName, String driverPhone) {
        List<String> people = new ArrayList<>();
        alert.getAnswers().forEach((userId, given) -> {
            if (given != answer) {
                return;
            }
            if (userId.equals(route.getDriverId())) {
                people.add(0, "driver " + (driverName == null ? "" : driverName + " ") + (driverPhone == null ? "" : driverPhone));
            } else {
                String phone = identityServiceClient.fetchPhone(userId);
                people.add("rider " + (phone == null ? userId : phone));
            }
        });
        return people.isEmpty() ? null : people.stream().map(String::trim).collect(Collectors.joining(", "));
    }

    @Transactional(readOnly = true)
    public Page<TripSafetyAlert> recent(Pageable pageable) {
        return alertRepository.findAllByOrderByCreatedAtDesc(pageable);
    }

    private List<Booking> ridersOnTrip(String routePostId) {
        return bookingRepository.findByRoutePostId(routePostId).stream()
                .filter(b -> ON_TRIP.contains(b.getStatus())).toList();
    }
}
