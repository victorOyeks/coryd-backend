package org.coryd.trip.service;

import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Subquery;
import lombok.RequiredArgsConstructor;
import org.coryd.common.enums.BookingStatus;
import org.coryd.common.enums.LiveTripPhase;
import org.coryd.common.enums.OpsTripStatus;
import org.coryd.common.enums.RouteStatus;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.common.response.PagedResponse;
import org.coryd.trip.config.StuckTripProperties;
import org.coryd.trip.dto.LocationResponse;
import org.coryd.trip.dto.OpsCancellationCounts;
import org.coryd.trip.dto.OpsCancellationFilter;
import org.coryd.trip.dto.OpsCancellationInfo;
import org.coryd.trip.dto.OpsLiveTripItem;
import org.coryd.trip.dto.OpsStuckTripItem;
import org.coryd.trip.dto.OpsTimelineEntry;
import org.coryd.trip.dto.OpsTripCountsResponse;
import org.coryd.trip.dto.OpsTripDetailResponse;
import org.coryd.trip.dto.OpsTripFilter;
import org.coryd.trip.dto.OpsTripItem;
import org.coryd.trip.dto.OpsTripSort;
import org.coryd.trip.entity.Booking;
import org.coryd.trip.entity.BookingStatusHistory;
import org.coryd.trip.entity.CancellationLog;
import org.coryd.trip.entity.CancellationSource;
import org.coryd.trip.entity.OpsAuditLog;
import org.coryd.trip.entity.RoutePost;
import org.coryd.trip.entity.RouteTimeChange;
import org.coryd.trip.entity.SosIncident;
import org.coryd.trip.entity.TripSafetyAlert;
import org.coryd.trip.repository.BookingRepository;
import org.coryd.trip.repository.BookingStatusHistoryRepository;
import org.coryd.trip.repository.CancellationLogRepository;
import org.coryd.trip.repository.OpsAuditLogRepository;
import org.coryd.trip.repository.RatingRepository;
import org.coryd.trip.repository.RoutePostRepository;
import org.coryd.trip.repository.RouteTimeChangeRepository;
import org.coryd.trip.repository.SosIncidentRepository;
import org.coryd.trip.repository.TripSafetyAlertRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Raw trip data for the Ops Console - served through /internal/ops (ops-service only), which adds the
 * driver, vehicle and rider details from profile-service and identity-service. A "trip" here is a route
 * post: one driver, one vehicle, one departure, every booking on it.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OpsTripQueryService {

    static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");
    private static final List<RouteStatus> ACTIVE = List.of(RouteStatus.PUBLISHED, RouteStatus.FULL, RouteStatus.IN_PROGRESS);
    /** Riders carried or still booked - what seatsBooked and totalFare are made of, same as the driver's receipt. */
    private static final List<BookingStatus> CARRIED = List.of(BookingStatus.CONFIRMED, BookingStatus.EN_ROUTE_TO_PICKUP,
            BookingStatus.AT_PICKUP, BookingStatus.IN_PROGRESS, BookingStatus.COMPLETED);
    private static final List<BookingStatus> AWAITING_PICKUP = List.of(BookingStatus.CONFIRMED, BookingStatus.EN_ROUTE_TO_PICKUP,
            BookingStatus.AT_PICKUP);
    private static final Set<BookingStatus> OPS_CANCELLABLE = Set.of(BookingStatus.HELD, BookingStatus.RESERVED,
            BookingStatus.CONFIRMED, BookingStatus.EN_ROUTE_TO_PICKUP, BookingStatus.AT_PICKUP, BookingStatus.IN_PROGRESS);
    /** "T-2F2F53" (a trip) or "D-B062D8" (a driver) - the references the console shows. */
    private static final Pattern REF = Pattern.compile("^([TtDd])-([0-9a-fA-F]{1,8})$");

    private final RoutePostRepository routePostRepository;
    private final BookingRepository bookingRepository;
    private final RatingRepository ratingRepository;
    private final LocationService locationService;
    private final StuckTripProperties stuckTripProperties;
    private final CancellationLogRepository cancellationLogRepository;
    private final OpsAuditLogRepository opsAuditLogRepository;
    private final BookingStatusHistoryRepository historyRepository;
    private final RouteTimeChangeRepository routeTimeChangeRepository;
    private final SosIncidentRepository sosIncidentRepository;
    private final TripSafetyAlertRepository tripSafetyAlertRepository;
    private final BookingService bookingService;

    // ---- trips table ---------------------------------------------------------------------------------------------

    /** Trips table, newest departure first. A null status is the "All" tab. */
    public PagedResponse<OpsTripItem> trips(OpsTripStatus status, String search, Pageable pageable) {
        return trips(OpsTripFilter.of(status, search), null, null, pageable);
    }

    /** Filtered, sorted and paged in the database. A null sort is newest departure first. */
    public PagedResponse<OpsTripItem> trips(OpsTripFilter filter, OpsTripSort sort, Sort.Direction direction, Pageable pageable) {
        OpsTripSort by = sort == null ? OpsTripSort.DEPARTURE_TIME : sort;
        Sort.Direction dir = direction == null ? Sort.Direction.DESC : direction;
        Sort order = Sort.by(dir, by.property()).and(Sort.by(Sort.Direction.DESC, "id"));
        Pageable sorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), order);
        Page<RoutePost> routes = routePostRepository.findAll(spec(filter, statusesOf(filter)), sorted);
        return PagedResponse.from(new PageImpl<>(items(routes.getContent()), sorted, routes.getTotalElements()));
    }

    /** The tab badges - they follow the same search as the table. */
    public OpsTripCountsResponse counts(String search) {
        return tripCounts(OpsTripFilter.of(null, search));
    }

    /** Every badge follows the table's filters except its status. */
    public OpsTripCountsResponse tripCounts(OpsTripFilter filter) {
        OpsTripFilter any = filter.withStatus(null, null);
        long inProgress = count(any, List.of(RouteStatus.IN_PROGRESS));
        Instant now = Instant.now();
        long stuck = inProgress == 0 ? 0 : routePostRepository.findAll(spec(any, List.of(RouteStatus.IN_PROGRESS))).stream()
                .filter(r -> stuckTripProperties.isStuck(r, now))
                .count();
        return new OpsTripCountsResponse(
                count(any, List.of(RouteStatus.values())),
                count(any, ACTIVE),
                count(any, List.of(RouteStatus.COMPLETED)),
                count(any, List.of(RouteStatus.CANCELLED)),
                count(any, List.of(RouteStatus.PUBLISHED)),
                count(any, List.of(RouteStatus.FULL)),
                inProgress,
                stuck);
    }

    private long count(OpsTripFilter filter, List<RouteStatus> statuses) {
        return routePostRepository.count(spec(filter, statuses));
    }

    private static Specification<RoutePost> spec(OpsTripFilter filter, List<RouteStatus> statuses) {
        return (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            where.add(statuses.isEmpty() ? cb.disjunction() : root.get("status").in(statuses));
            if (filter.from() != null) {
                where.add(cb.greaterThanOrEqualTo(root.get("departureTime"), startOfLagosDay(filter.from())));
            }
            if (filter.to() != null) {
                where.add(cb.lessThan(root.get("departureTime"), startOfLagosDay(filter.to().plusDays(1))));
            }
            if (notBlank(filter.driverId())) {
                where.add(cb.equal(root.get("driverId"), filter.driverId().trim()));
            }
            if (notBlank(filter.riderId())) {
                Subquery<String> paidSeat = query.subquery(String.class);
                var booking = paidSeat.from(Booking.class);
                paidSeat.select(booking.get("routePostId")).where(
                        cb.equal(booking.get("routePostId"), root.get("id")),
                        cb.equal(booking.get("riderId"), filter.riderId().trim()),
                        cb.isNotNull(booking.get("confirmedAt")));
                where.add(cb.exists(paidSeat));
            }
            if (notBlank(filter.search())) {
                String term = filter.search().trim().toLowerCase(Locale.ROOT);
                Matcher ref = REF.matcher(term);
                if (ref.matches()) {
                    String prefix = ref.group(2) + "%";
                    where.add(ref.group(1).equals("t")
                            ? cb.like(cb.lower(root.get("id")), prefix)
                            : cb.like(cb.lower(root.get("driverId")), prefix));
                } else {
                    String like = "%" + escapeLike(term) + "%";
                    where.add(cb.or(
                            cb.like(cb.lower(root.get("originLabel")), like, '\\'),
                            cb.like(cb.lower(root.get("destinationLabel")), like, '\\'),
                            cb.like(cb.lower(root.get("id")), escapeLike(term) + "%", '\\')));
                }
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
    }

    // ---- one trip ------------------------------------------------------------------------------------------------

    public OpsTripDetailResponse trip(String routePostId) {
        RoutePost route = routePostRepository.findById(routePostId)
                .orElseThrow(() -> new ResourceNotFoundException("Trip not found"));
        Instant now = Instant.now();
        OpsTripItem item = items(List.of(route)).getFirst();
        List<Booking> bookings = bookingRepository.findByRoutePostId(routePostId);
        List<CancellationLog> cancellations = cancellationLogRepository.findByRoutePostIdOrderByCancelledAtAsc(routePostId);
        Map<String, CancellationLog> seatCancellations = cancellations.stream()
                .filter(c -> c.getBookingId() != null)
                .collect(Collectors.toMap(CancellationLog::getBookingId, Function.identity(), (a, b) -> a));
        CancellationLog routeCancellation = cancellations.stream()
                .filter(c -> c.getKind() == CancellationLog.Kind.ROUTE).reduce((a, b) -> b).orElse(null);

        // Every booking that was ever paid for - cancellations and no-shows included, so ops sees what happened.
        List<Booking> paid = bookings.stream()
                .filter(b -> b.getConfirmedAt() != null || seatCancellations.containsKey(b.getId()))
                .sorted(Comparator.comparing(b -> b.getConfirmedAt() != null ? b.getConfirmedAt() : b.getCreatedAt()))
                .toList();
        List<BookingStatusHistory> history = paid.isEmpty() ? List.of()
                : historyRepository.findByBookingIdInOrderByCreatedAtAsc(paid.stream().map(Booking::getId).toList());
        Map<String, Instant> completedAt = history.stream()
                .filter(h -> h.getStatus() == BookingStatus.COMPLETED)
                .collect(Collectors.toMap(BookingStatusHistory::getBookingId, BookingStatusHistory::getCreatedAt, (a, b) -> b));
        boolean live = route.getStatus() != RouteStatus.CANCELLED && route.getStatus() != RouteStatus.COMPLETED;

        List<OpsTripDetailResponse.Passenger> passengers = paid.stream()
                .map(b -> {
                    CancellationLog c = seatCancellations.get(b.getId());
                    boolean cancellable = live && OPS_CANCELLABLE.contains(b.getStatus());
                    return new OpsTripDetailResponse.Passenger(b.getId(), b.getRiderId(), b.getSeatCount(), b.getFareTotal(),
                            b.getStatus(), b.getPickupLabel(), b.getPickupLat(), b.getPickupLng(), b.getConfirmedAt(),
                            b.getPickupConfirmedAt(), b.getDropoffLabel(), completedAt.get(b.getId()), cancellable,
                            cancellable ? bookingService.riderFaultFee(b, route, now) : BigDecimal.ZERO,
                            c == null ? null : OpsCancellationInfo.of(c, null));
                })
                .toList();

        LocationResponse lastSeen = locationService.lastSeenRouteLocation(routePostId);
        boolean stuck = stuckTripProperties.isStuck(route, now);
        List<String> childIds = routeCancellation == null ? null : cancellations.stream()
                .filter(c -> routeCancellation.getId().equals(c.getParentId())).map(CancellationLog::getBookingId).toList();

        return new OpsTripDetailResponse(item, route.getOriginLat(), route.getOriginLng(), route.getDestinationLat(), route.getDestinationLng(),
                route.getRouteNote(), route.getEstimatedDurationMinutes(),
                route.getStatus() == RouteStatus.IN_PROGRESS ? locationService.peekRouteLocation(routePostId) : null,
                passengers, route.getCreatedAt(), route.getOriginalDepartureTime(), route.getTimeChangeCount(),
                stuckTripProperties.expectedArrival(route), stuck, stuck ? stuckTripProperties.stuckFrom(route) : null,
                lastSeen, ageSeconds(lastSeen, now),
                routeCancellation == null ? null : OpsCancellationInfo.of(routeCancellation, childIds),
                new OpsTripDetailResponse.Actions(live, stuck),
                timeline(route, paid, history, cancellations));
    }

    private List<OpsTimelineEntry> timeline(RoutePost route, List<Booking> paid, List<BookingStatusHistory> history,
                                            List<CancellationLog> cancellations) {
        List<OpsTimelineEntry> entries = new ArrayList<>();
        String driverId = route.getDriverId();
        entries.add(new OpsTimelineEntry(route.getCreatedAt(), "POSTED", "DRIVER", driverId, null, null, null,
                route.getOriginLabel() + " → " + route.getDestinationLabel() + ", leaving " + TripScheduleService.formatLagos(
                        route.getOriginalDepartureTime() != null ? route.getOriginalDepartureTime() : route.getDepartureTime())));
        for (RouteTimeChange change : routeTimeChangeRepository.findByRoutePostIdOrderByCreatedAtAsc(route.getId())) {
            entries.add(new OpsTimelineEntry(change.getCreatedAt(), "TIME_CHANGED", "DRIVER", change.getDriverId(), null, null, null,
                    "Departure moved from " + TripScheduleService.formatLagos(change.getPreviousDepartureTime()) + " to "
                            + TripScheduleService.formatLagos(change.getNewDepartureTime())
                            + (change.getRidersBooked() > 0 ? " with " + change.getRidersBooked() + " booked" : "")
                            + (change.isLastMinute() ? " (last minute)" : "")));
        }

        Map<String, Booking> byId = paid.stream().collect(Collectors.toMap(Booking::getId, Function.identity()));
        Set<String> cancelledByLog = cancellations.stream().map(CancellationLog::getBookingId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        for (BookingStatusHistory h : history) {
            Booking b = byId.get(h.getBookingId());
            if (b == null || h.getStatus() == BookingStatus.HELD || h.getStatus() == BookingStatus.RESERVED) {
                continue;
            }
            boolean cancelled = h.getStatus() == BookingStatus.CANCELLED_RIDER || h.getStatus() == BookingStatus.CANCELLED_DRIVER;
            if (cancelled && cancelledByLog.contains(b.getId())) {
                continue; // the log's own entry says more - who, why, the fee
            }
            String type = switch (h.getStatus()) {
                case CONFIRMED -> h.getNote() != null && h.getNote().startsWith("Payment") ? "BOOKED" : "BOOKING_UPDATED";
                case EN_ROUTE_TO_PICKUP -> "DRIVER_EN_ROUTE";
                case AT_PICKUP -> "DRIVER_ARRIVED";
                case IN_PROGRESS -> "PICKED_UP";
                case NO_SHOW -> "NO_SHOW";
                case COMPLETED -> "BOOKING_COMPLETED";
                case CANCELLED_RIDER, CANCELLED_DRIVER -> "BOOKING_CANCELLED";
                default -> "BOOKING_UPDATED";
            };
            String actor = switch (type) {
                case "BOOKED" -> "RIDER";
                case "BOOKING_CANCELLED" -> h.getStatus() == BookingStatus.CANCELLED_RIDER ? "RIDER" : "DRIVER";
                default -> "DRIVER";
            };
            String actorId = "RIDER".equals(actor) ? b.getRiderId() : driverId;
            if (h.getNote() != null && h.getNote().contains("CoRyd support")) {
                actor = "OPS";
                actorId = null;
            }
            entries.add(new OpsTimelineEntry(h.getCreatedAt(), type, actor, actorId, null, b.getId(), b.getRiderId(), h.getNote()));
        }

        if (route.getStartedAt() != null) {
            entries.add(new OpsTimelineEntry(route.getStartedAt(), "STARTED", "DRIVER", driverId, null, null, null, null));
        }
        List<OpsAuditLog> audit = opsAuditLogRepository.findByRoutePostIdOrderByCreatedAtAsc(route.getId());
        if (route.getCompletedAt() != null) {
            OpsAuditLog byOps = audit.stream().filter(a -> a.getAction() == OpsAuditLog.Action.RESOLVE_STUCK_COMPLETED).findFirst().orElse(null);
            entries.add(byOps == null
                    ? new OpsTimelineEntry(route.getCompletedAt(), "COMPLETED", "DRIVER", driverId, null, null, null, null)
                    : new OpsTimelineEntry(route.getCompletedAt(), "COMPLETED", "OPS", byOps.getActorId(), byOps.getActorName(),
                            null, null, byOps.getReason()));
        }
        for (CancellationLog c : cancellations) {
            boolean seat = c.getKind() == CancellationLog.Kind.BOOKING;
            String detail = (c.getReason() == null ? "" : c.getReason())
                    + (c.getFault() == null ? "" : (c.getReason() == null ? "" : " - ") + c.getFault().name().toLowerCase(Locale.ROOT) + " fault")
                    + (seat && c.getFeeAmount().signum() > 0 ? ", ₦" + c.getFeeAmount().toPlainString() + " late fee kept" : "");
            entries.add(new OpsTimelineEntry(c.getCancelledAt(), seat ? "BOOKING_CANCELLED" : "CANCELLED", actorOf(c.getSource()),
                    c.getActorId(), c.getActorName(), c.getBookingId(), c.getRiderId(), detail.isBlank() ? null : detail));
        }
        List<String> bookingIds = paid.stream().map(Booking::getId).toList();
        for (SosIncident sos : sosIncidentRepository.findByRoutePostIdOrBookingIdIn(route.getId(),
                bookingIds.isEmpty() ? List.of("-") : bookingIds)) {
            entries.add(new OpsTimelineEntry(sos.getCreatedAt(), "SOS", sos.getTriggeredByRole() == null ? "RIDER" : sos.getTriggeredByRole(),
                    sos.getTriggeredBy(), null, sos.getBookingId(), null,
                    sos.getLatitude() == null ? null : "At " + sos.getLatitude() + "," + sos.getLongitude()));
        }
        for (TripSafetyAlert alert : tripSafetyAlertRepository.findByRoutePostIdOrderByCreatedAtAsc(route.getId())) {
            entries.add(new OpsTimelineEntry(alert.getCreatedAt(), "SAFETY_CHECK", "SYSTEM", null, null, null, null,
                    alert.getKind() + " - " + alert.getStatus()));
        }
        for (OpsAuditLog a : audit) {
            entries.add(new OpsTimelineEntry(a.getCreatedAt(), "OPS_ACTION", "OPS", a.getActorId(), a.getActorName(),
                    a.getTargetType() == OpsAuditLog.TargetType.BOOKING ? a.getTargetId() : null, null,
                    a.getAction() + ": " + a.getReason()));
        }
        entries.sort(Comparator.comparing(OpsTimelineEntry::at, Comparator.nullsFirst(Comparator.naturalOrder())));
        return entries;
    }

    private static String actorOf(CancellationSource source) {
        return switch (source) {
            case RIDER -> "RIDER";
            case DRIVER -> "DRIVER";
            case OPS, SUSPENSION -> "OPS";
            case ACCOUNT_DELETED -> "SYSTEM";
            case SYSTEM, SYSTEM_EXPIRY -> "SYSTEM";
        };
    }

    // ---- live and stuck ------------------------------------------------------------------------------------------

    /** Started trips plus those inside the 30-minute boarding window, soonest departure first. */
    public List<OpsLiveTripItem> live(String search) {
        Instant now = Instant.now();
        String term = search == null || search.isBlank() ? null : search.trim().toLowerCase(Locale.ROOT);
        List<RoutePost> routes = routePostRepository.findLiveForOps(now.plus(TripScheduleService.ACTIVE_WINDOW)).stream()
                .filter(r -> term == null || matches(r, term))
                .toList();
        if (routes.isEmpty()) {
            return List.of();
        }
        Map<String, Integer> seats = seatsBooked(routes);
        Map<String, RatingRepository.RatingSummary> ratings = ratings(routes);

        return routes.stream().map(r -> {
            boolean started = r.getStatus() == RouteStatus.IN_PROGRESS;
            int duration = r.getEstimatedDurationMinutes() == null
                    ? TripScheduleService.DEFAULT_DURATION_MINUTES : r.getEstimatedDurationMinutes();
            Instant start = started && r.getStartedAt() != null ? r.getStartedAt() : r.getDepartureTime();
            long elapsed = started ? Math.max(0, Duration.between(start, now).toMinutes()) : 0;
            double progress = started ? Math.min(1.0, elapsed / (double) Math.max(duration, 1)) : 0.0;
            Instant eta = start.plus(Duration.ofMinutes(duration));
            RatingRepository.RatingSummary rating = ratings.get(r.getDriverId());
            LocationResponse lastSeen = started ? locationService.lastSeenRouteLocation(r.getId()) : null;
            return new OpsLiveTripItem(r.getId(), r.getDriverId(), r.getOriginLabel(), r.getDestinationLabel(),
                    r.getOriginLat(), r.getOriginLng(), r.getDestinationLat(), r.getDestinationLng(),
                    r.getDepartureTime(), r.getStartedAt(),
                    started ? LiveTripPhase.ACTIVE : LiveTripPhase.BOARDING,
                    r.getSeatCapacity(), seats.getOrDefault(r.getId(), 0), duration, elapsed,
                    eta, progress,
                    rating == null ? null : rating.getAvgStars(), rating == null ? 0 : rating.getRatingCount(),
                    started ? locationService.peekRouteLocation(r.getId()) : null,
                    lastSeen, ageSeconds(lastSeen, now), lastSeen != null && !lastSeen.available(),
                    started && now.isAfter(eta), stuckTripProperties.isStuck(r, now));
        }).toList();
    }

    /** In-progress trips past their expected arrival plus the grace - longest overdue first. */
    public PagedResponse<OpsStuckTripItem> stuck(Pageable pageable) {
        Instant now = Instant.now();
        List<RoutePost> stuck = routePostRepository.findByStatus(RouteStatus.IN_PROGRESS).stream()
                .filter(r -> stuckTripProperties.isStuck(r, now))
                .sorted(Comparator.comparing(stuckTripProperties::expectedArrival).thenComparing(RoutePost::getId))
                .toList();
        int from = (int) Math.min(pageable.getOffset(), stuck.size());
        List<RoutePost> page = stuck.subList(from, Math.min(from + pageable.getPageSize(), stuck.size()));
        Map<String, OpsTripItem> items = items(page).stream().collect(Collectors.toMap(OpsTripItem::routePostId, Function.identity()));
        Map<String, List<Booking>> bookings = page.isEmpty() ? Map.of()
                : bookingRepository.findByRoutePostIdIn(page.stream().map(RoutePost::getId).toList()).stream()
                .collect(Collectors.groupingBy(Booking::getRoutePostId));

        List<OpsStuckTripItem> content = page.stream().map(r -> {
            List<Booking> onIt = bookings.getOrDefault(r.getId(), List.of());
            Instant expected = stuckTripProperties.expectedArrival(r);
            LocationResponse lastSeen = locationService.lastSeenRouteLocation(r.getId());
            return new OpsStuckTripItem(items.get(r.getId()), expected, stuckTripProperties.stuckFrom(r),
                    Math.max(0, Duration.between(expected, now).toMinutes()),
                    (int) onIt.stream().filter(b -> b.getStatus() == BookingStatus.IN_PROGRESS).count(),
                    (int) onIt.stream().filter(b -> AWAITING_PICKUP.contains(b.getStatus())).count(),
                    (int) onIt.stream().filter(b -> b.getStatus() == BookingStatus.COMPLETED).count(),
                    lastSeen, ageSeconds(lastSeen, now));
        }).toList();
        return PagedResponse.from(new PageImpl<>(content, pageable, stuck.size()));
    }

    // ---- cancellations log ---------------------------------------------------------------------------------------

    /** Newest first. */
    public PagedResponse<OpsCancellationInfo> cancellations(OpsCancellationFilter filter, Pageable pageable) {
        Pageable sorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Direction.DESC, "cancelledAt").and(Sort.by(Sort.Direction.DESC, "id")));
        Page<CancellationLog> page = cancellationLogRepository.findAll(cancellationSpec(filter), sorted);
        List<String> routeRowIds = page.getContent().stream()
                .filter(c -> c.getKind() == CancellationLog.Kind.ROUTE).map(CancellationLog::getId).toList();
        Map<String, List<String>> children = routeRowIds.isEmpty() ? Map.of()
                : cancellationLogRepository.findByParentIdIn(routeRowIds).stream()
                .collect(Collectors.groupingBy(CancellationLog::getParentId,
                        Collectors.mapping(CancellationLog::getBookingId, Collectors.toList())));
        List<OpsCancellationInfo> content = page.getContent().stream()
                .map(c -> OpsCancellationInfo.of(c, children.get(c.getId()))).toList();
        return PagedResponse.from(new PageImpl<>(content, sorted, page.getTotalElements()));
    }

    public OpsCancellationCounts cancellationCounts(OpsCancellationFilter filter) {
        Function<CancellationSource, Long> count = source -> cancellationLogRepository.count(cancellationSpec(filter.withSource(source)));
        return new OpsCancellationCounts(count.apply(null), count.apply(CancellationSource.RIDER), count.apply(CancellationSource.DRIVER),
                count.apply(CancellationSource.SYSTEM_EXPIRY), count.apply(CancellationSource.SYSTEM), count.apply(CancellationSource.OPS),
                count.apply(CancellationSource.SUSPENSION), count.apply(CancellationSource.ACCOUNT_DELETED));
    }

    private static Specification<CancellationLog> cancellationSpec(OpsCancellationFilter filter) {
        return (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            if (filter.source() != null) {
                where.add(cb.equal(root.get("source"), filter.source()));
            }
            if (filter.fault() != null) {
                where.add(cb.equal(root.get("fault"), filter.fault()));
            }
            if (notBlank(filter.routePostId())) {
                where.add(cb.equal(root.get("routePostId"), filter.routePostId().trim()));
            }
            if (filter.kind() != null) {
                where.add(cb.equal(root.get("kind"), filter.kind()));
            } else if (!notBlank(filter.routePostId())) {
                // Each cancellation once: a whole trip's seats are folded into its ROUTE row.
                where.add(cb.isNull(root.get("parentId")));
            }
            if (filter.from() != null) {
                where.add(cb.greaterThanOrEqualTo(root.get("cancelledAt"), startOfLagosDay(filter.from())));
            }
            if (filter.to() != null) {
                where.add(cb.lessThan(root.get("cancelledAt"), startOfLagosDay(filter.to().plusDays(1))));
            }
            if (notBlank(filter.driverId())) {
                where.add(cb.equal(root.get("driverId"), filter.driverId().trim()));
            }
            if (notBlank(filter.riderId())) {
                where.add(cb.equal(root.get("riderId"), filter.riderId().trim()));
            }
            if (notBlank(filter.search())) {
                String term = filter.search().trim().toLowerCase(Locale.ROOT);
                Matcher ref = REF.matcher(term);
                if (ref.matches()) {
                    String prefix = ref.group(2) + "%";
                    where.add(ref.group(1).equals("t")
                            ? cb.like(cb.lower(root.get("routePostId")), prefix)
                            : cb.like(cb.lower(root.get("driverId")), prefix));
                } else {
                    String like = "%" + escapeLike(term) + "%";
                    where.add(cb.or(
                            cb.like(cb.lower(root.get("originLabel")), like, '\\'),
                            cb.like(cb.lower(root.get("destinationLabel")), like, '\\'),
                            cb.like(cb.lower(root.get("reason")), like, '\\'),
                            cb.like(cb.lower(root.get("routePostId")), escapeLike(term) + "%", '\\'),
                            cb.like(cb.lower(root.get("bookingId")), escapeLike(term) + "%", '\\')));
                }
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
    }

    // ---- shared --------------------------------------------------------------------------------------------------

    private List<OpsTripItem> items(List<RoutePost> routes) {
        if (routes.isEmpty()) {
            return List.of();
        }
        Map<String, List<Booking>> carried = bookingRepository.findByRoutePostIdIn(routes.stream().map(RoutePost::getId).toList()).stream()
                .filter(b -> CARRIED.contains(b.getStatus()))
                .collect(Collectors.groupingBy(Booking::getRoutePostId));
        Map<String, RatingRepository.RatingSummary> ratings = ratings(routes);

        return routes.stream().map(r -> {
            List<Booking> booked = carried.getOrDefault(r.getId(), List.of());
            RatingRepository.RatingSummary rating = ratings.get(r.getDriverId());
            return new OpsTripItem(r.getId(), r.getDriverId(), r.getVehicleId(), r.getOriginLabel(), r.getDestinationLabel(),
                    r.getDepartureTime(), r.getStatus(), r.getSeatCapacity(), r.getSeatsAvailable(),
                    booked.stream().mapToInt(Booking::getSeatCount).sum(), r.getFarePerSeat(),
                    booked.stream().map(Booking::getFareTotal).reduce(BigDecimal.ZERO, BigDecimal::add),
                    rating == null ? null : rating.getAvgStars(), rating == null ? 0 : rating.getRatingCount(),
                    r.getStartedAt(), r.getCompletedAt());
        }).toList();
    }

    private Map<String, Integer> seatsBooked(List<RoutePost> routes) {
        return bookingRepository.findByRoutePostIdIn(routes.stream().map(RoutePost::getId).toList()).stream()
                .filter(b -> CARRIED.contains(b.getStatus()))
                .collect(Collectors.groupingBy(Booking::getRoutePostId, Collectors.summingInt(Booking::getSeatCount)));
    }

    private Map<String, RatingRepository.RatingSummary> ratings(List<RoutePost> routes) {
        List<String> driverIds = routes.stream().map(RoutePost::getDriverId).distinct().toList();
        return ratingRepository.summarize(driverIds).stream()
                .collect(Collectors.toMap(RatingRepository.RatingSummary::getRateeId, Function.identity()));
    }

    private static Long ageSeconds(LocationResponse location, Instant now) {
        return location == null || location.recordedAt() == null ? null
                : Math.max(0, Duration.between(location.recordedAt(), now).toSeconds());
    }

    private static boolean matches(RoutePost route, String term) {
        return route.getId().toLowerCase(Locale.ROOT).startsWith(term)
                || route.getOriginLabel().toLowerCase(Locale.ROOT).contains(term)
                || route.getDestinationLabel().toLowerCase(Locale.ROOT).contains(term);
    }

    private static List<RouteStatus> statusesOf(OpsTripFilter filter) {
        List<RouteStatus> group = statusesOf(filter.status());
        if (filter.routeStatuses() == null || filter.routeStatuses().isEmpty()) {
            return group;
        }
        return filter.routeStatuses().stream().filter(group::contains).toList();
    }

    private static List<RouteStatus> statusesOf(OpsTripStatus status) {
        if (status == null) {
            return List.of(RouteStatus.values());
        }
        return switch (status) {
            case ACTIVE -> ACTIVE;
            case COMPLETED -> List.of(RouteStatus.COMPLETED);
            case CANCELLED -> List.of(RouteStatus.CANCELLED);
        };
    }

    static Instant startOfLagosDay(LocalDate day) {
        return day.atStartOfDay(LAGOS).toInstant();
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String escapeLike(String term) {
        return term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
