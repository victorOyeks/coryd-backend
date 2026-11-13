package org.coryd.trip.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.enums.RouteStatus;
import org.coryd.common.exception.BadRequestException;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.trip.config.StuckTripProperties;
import org.coryd.trip.dto.OpsActionResult;
import org.coryd.trip.dto.OpsCancelRequest;
import org.coryd.trip.dto.OpsResolveRequest;
import org.coryd.trip.entity.Booking;
import org.coryd.trip.entity.CancellationFault;
import org.coryd.trip.entity.OpsAuditLog;
import org.coryd.trip.entity.RoutePost;
import org.coryd.trip.repository.BookingRepository;
import org.coryd.trip.repository.OpsAuditLogRepository;
import org.coryd.trip.repository.RoutePostRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The Ops Console's actions on trips and bookings - cancel a whole trip, cancel one seat, resolve a stuck trip. Each
 * goes through the same paths the driver's and rider's own actions use (so refunds, earnings and pushes follow the
 * normal events) and is written to the ops audit log - who, what, before, after, why - in the same transaction.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpsTripActionService {

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final RoutePostRepository routePostRepository;
    private final BookingRepository bookingRepository;
    private final RouteService routeService;
    private final BookingService bookingService;
    private final OpsAuditLogRepository opsAuditLogRepository;
    private final StuckTripProperties stuckTripProperties;

    /** Ops cancels a whole trip for the driver. Every paid rider is refunded in full - the fault is recorded, not charged. */
    @Transactional
    public OpsActionResult cancelTrip(String routePostId, OpsCancelRequest request) {
        String reason = requireReason(request.reason());
        if (request.fault() == CancellationFault.RIDER) {
            throw new BadRequestException("A whole trip can't be a rider's fault - choose DRIVER or PLATFORM, or cancel the rider's booking instead");
        }
        RoutePost route = route(routePostId);
        Map<String, Object> before = routeState(route);
        List<String> refunded = routeService.cancelAsOps(routePostId, request.actorId(), request.actorName(), reason, request.fault());
        Map<String, Object> after = Map.of("routeStatus", RouteStatus.CANCELLED, "refundedBookingIds", refunded);
        OpsAuditLog audit = audit(request.actorId(), request.actorName(), OpsAuditLog.Action.CANCEL_TRIP, OpsAuditLog.TargetType.ROUTE,
                routePostId, routePostId, before, after, reason, request.fault());
        log.info("OPS {} cancelled trip {} ({} fault): {} - {} paid seats refunded", request.actorId(), routePostId, request.fault(),
                reason, refunded.size());
        return new OpsActionResult(audit.getAction().name(), audit.getId(), routePostId, null, before.get("routeStatus").toString(),
                RouteStatus.CANCELLED.name(), fullRefunds(refunded), List.of(), List.of());
    }

    /**
     * Ops cancels one seat. RIDER fault keeps the late-cancellation fee under CancellationPolicy unless waived; DRIVER
     * and PLATFORM refund in full.
     */
    @Transactional
    public OpsActionResult cancelBooking(String bookingId, OpsCancelRequest request) {
        String reason = requireReason(request.reason());
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));
        boolean waive = Boolean.TRUE.equals(request.waiveFee());
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("bookingStatus", booking.getStatus());
        before.put("paid", booking.getConfirmedAt() != null);
        before.put("fareTotal", booking.getFareTotal());

        BookingService.OpsCancellation done = bookingService.cancelAsOps(bookingId, request.actorId(), request.actorName(), reason,
                request.fault(), waive);

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("bookingStatus", done.statusAfter());
        after.put("fee", done.fee());
        after.put("refund", done.refund());
        after.put("feeWaived", waive && request.fault() == CancellationFault.RIDER);
        OpsAuditLog audit = audit(request.actorId(), request.actorName(), OpsAuditLog.Action.CANCEL_BOOKING, OpsAuditLog.TargetType.BOOKING,
                bookingId, done.routePostId(), before, after, reason, request.fault());
        log.info("OPS {} cancelled booking {} ({} fault, fee {}): {}", request.actorId(), bookingId, request.fault(), done.fee(), reason);
        List<OpsActionResult.Refund> refunds = done.paid()
                ? List.of(new OpsActionResult.Refund(bookingId, booking.getRiderId(), done.fareTotal(), done.fee(), done.refund()))
                : List.of();
        return new OpsActionResult(audit.getAction().name(), audit.getId(), done.routePostId(), bookingId, done.statusBefore().name(),
                done.statusAfter().name(), refunds, List.of(), List.of());
    }

    /**
     * Resolves a trip left in progress past the stuck threshold: COMPLETED settles it as having happened, CANCELLED
     * refunds every paid rider. A trip that isn't stuck is refused - the driver's own Complete and Cancel are for that.
     */
    @Transactional
    public OpsActionResult resolve(String routePostId, OpsResolveRequest request) {
        String reason = requireReason(request.reason());
        RoutePost route = route(routePostId);
        if (route.getStatus() != RouteStatus.IN_PROGRESS) {
            throw new BadRequestException("Only a trip stuck in progress can be resolved - this one is " + route.getStatus());
        }
        if (!stuckTripProperties.isStuck(route, Instant.now())) {
            throw new BadRequestException("This trip isn't stuck yet - it counts as stuck " + stuckTripProperties.getGraceHours()
                    + "h after its expected arrival (" + TripScheduleService.formatLagos(stuckTripProperties.expectedArrival(route))
                    + "). Cancel it instead if it must end now.");
        }
        Map<String, Object> before = routeState(route);

        if (request.outcome() == OpsResolveRequest.Outcome.CANCELLED) {
            CancellationFault fault = request.fault() == null ? CancellationFault.PLATFORM : request.fault();
            if (fault == CancellationFault.RIDER) {
                throw new BadRequestException("A whole trip can't be a rider's fault - choose DRIVER or PLATFORM");
            }
            List<String> refunded = routeService.cancelAsOps(routePostId, request.actorId(), request.actorName(), reason, fault);
            Map<String, Object> after = Map.of("routeStatus", RouteStatus.CANCELLED, "refundedBookingIds", refunded);
            OpsAuditLog audit = audit(request.actorId(), request.actorName(), OpsAuditLog.Action.RESOLVE_STUCK_CANCELLED,
                    OpsAuditLog.TargetType.ROUTE, routePostId, routePostId, before, after, reason, fault);
            log.info("OPS {} resolved stuck trip {} as cancelled ({} fault): {}", request.actorId(), routePostId, fault, reason);
            return new OpsActionResult(audit.getAction().name(), audit.getId(), routePostId, null, RouteStatus.IN_PROGRESS.name(),
                    RouteStatus.CANCELLED.name(), fullRefunds(refunded), List.of(), List.of());
        }

        BookingService.OpsCompletion done = bookingService.completeAsOps(routePostId, request.actorId(), request.actorName(), reason,
                request.unboardedBookings());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("routeStatus", RouteStatus.COMPLETED);
        after.put("completedBookingIds", done.completed());
        after.put("refundedBookingIds", done.refunded());
        after.put("noShowBookingIds", done.noShows());
        after.put("releasedBookingIds", done.released());
        OpsAuditLog audit = audit(request.actorId(), request.actorName(), OpsAuditLog.Action.RESOLVE_STUCK_COMPLETED,
                OpsAuditLog.TargetType.ROUTE, routePostId, routePostId, before, after, reason, null);
        log.info("OPS {} resolved stuck trip {} as completed: {} - {} completed, {} refunded, {} no-shows", request.actorId(),
                routePostId, reason, done.completed().size(), done.refunded().size(), done.noShows().size());
        return new OpsActionResult(audit.getAction().name(), audit.getId(), routePostId, null, RouteStatus.IN_PROGRESS.name(),
                RouteStatus.COMPLETED.name(), fullRefunds(done.refunded()), done.completed(), done.noShows());
    }

    private RoutePost route(String routePostId) {
        return routePostRepository.findById(routePostId).orElseThrow(() -> new ResourceNotFoundException("Trip not found"));
    }

    private static String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BadRequestException("A reason is required");
        }
        return reason.trim();
    }

    private Map<String, Object> routeState(RoutePost route) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("routeStatus", route.getStatus());
        state.put("seatsAvailable", route.getSeatsAvailable());
        state.put("departureTime", route.getDepartureTime());
        state.put("startedAt", route.getStartedAt());
        state.put("bookings", bookingRepository.findByRoutePostId(route.getId()).stream()
                .map(b -> Map.of("bookingId", b.getId(), "status", b.getStatus(), "paid", b.getConfirmedAt() != null))
                .toList());
        return state;
    }

    private List<OpsActionResult.Refund> fullRefunds(List<String> bookingIds) {
        if (bookingIds.isEmpty()) {
            return List.of();
        }
        Map<String, Booking> byId = bookingRepository.findAllById(bookingIds).stream()
                .collect(Collectors.toMap(Booking::getId, Function.identity()));
        return bookingIds.stream().map(byId::get).filter(java.util.Objects::nonNull)
                .map(b -> new OpsActionResult.Refund(b.getId(), b.getRiderId(), b.getFareTotal(), BigDecimal.ZERO, b.getFareTotal()))
                .toList();
    }

    private OpsAuditLog audit(String actorId, String actorName, OpsAuditLog.Action action, OpsAuditLog.TargetType targetType,
                              String targetId, String routePostId, Object before, Object after, String reason, CancellationFault fault) {
        return opsAuditLogRepository.save(OpsAuditLog.builder()
                .actorId(actorId)
                .actorName(actorName)
                .action(action)
                .targetType(targetType)
                .targetId(targetId)
                .routePostId(routePostId)
                .beforeState(json(before))
                .afterState(json(after))
                .reason(reason)
                .fault(fault)
                .build());
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }
}
