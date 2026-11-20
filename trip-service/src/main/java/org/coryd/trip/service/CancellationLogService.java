package org.coryd.trip.service;

import lombok.RequiredArgsConstructor;
import org.coryd.common.enums.BookingStatus;
import org.coryd.common.enums.RouteStatus;
import org.coryd.trip.client.PaymentServiceClient;
import org.coryd.trip.dto.CancelledBy;
import org.coryd.trip.entity.Booking;
import org.coryd.trip.entity.CancellationFault;
import org.coryd.trip.entity.CancellationLog;
import org.coryd.trip.entity.CancellationSource;
import org.coryd.trip.entity.RoutePost;
import org.coryd.trip.repository.CancellationLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Writes the Cancellations Log - called from every place a trip or a paid seat ends, inside that cancellation's own
 * transaction, so the log can't say something happened that was rolled back.
 */
@Service
@RequiredArgsConstructor
public class CancellationLogService {

    private final CancellationLogRepository cancellationLogRepository;
    private final PaymentServiceClient paymentServiceClient;

    /**
     * Who cancelled and why. actorId is the rider, the driver or the ops admin; null when the system did it. A null
     * fault or reason falls back to what the source implies.
     */
    public record Context(String cancelledBy, String actorId, String actorName, String reason, CancellationFault fault) {

        public CancellationSource source() {
            return CancellationSource.fromCancelledBy(cancelledBy);
        }

        CancellationFault faultOrDefault() {
            return fault != null ? fault : source().defaultFault();
        }

        String reasonOrDefault() {
            if (reason != null) {
                return reason;
            }
            return switch (source()) {
                case SYSTEM_EXPIRY -> "Driver didn't start the trip in time";
                case SYSTEM -> "Payment arrived after the seat could no longer be given - refunded";
                case SUSPENSION -> "Account suspended";
                case ACCOUNT_DELETED -> "Account closed";
                default -> null;
            };
        }

        /** A trip or seat ended by someone on it - the driver or rider is the actor, unless it was the system. */
        public static Context of(String cancelledBy, String userId) {
            CancellationSource source = CancellationSource.fromCancelledBy(cancelledBy);
            boolean byPerson = source == CancellationSource.RIDER || source == CancellationSource.DRIVER
                    || source == CancellationSource.ACCOUNT_DELETED;
            return new Context(cancelledBy, byPerson ? userId : null, null, null, null);
        }
    }

    @Transactional
    public CancellationLog recordRoute(RoutePost route, RouteStatus statusBefore, Context context) {
        Instant now = Instant.now();
        return cancellationLogRepository.save(CancellationLog.builder()
                .kind(CancellationLog.Kind.ROUTE)
                .routePostId(route.getId())
                .driverId(route.getDriverId())
                .source(context.source())
                .cancelledBy(context.cancelledBy())
                .actorId(context.actorId())
                .actorName(context.actorName())
                .reason(context.reasonOrDefault())
                .fault(context.faultOrDefault())
                .cancelledAt(now)
                .departureTime(route.getDepartureTime())
                .minutesBeforeDeparture(minutesBefore(route.getDepartureTime(), now))
                .routeStatusBefore(statusBefore == null ? null : statusBefore.name())
                .seatCount(0)
                .fareTotal(BigDecimal.ZERO)
                .originLabel(route.getOriginLabel())
                .destinationLabel(route.getDestinationLabel())
                .build());
    }

    /** Adds one paid seat the route cancellation ended to its ROUTE row's totals. */
    @Transactional
    public void addToRoute(CancellationLog routeRow, CancellationLog bookingRow) {
        routeRow.setBookingsAffected(routeRow.getBookingsAffected() + 1);
        routeRow.setSeatCount(routeRow.getSeatCount() + (bookingRow.getSeatCount() == null ? 0 : bookingRow.getSeatCount()));
        routeRow.setFareTotal(routeRow.getFareTotal().add(nz(bookingRow.getFareTotal())));
        routeRow.setRefundExpected(routeRow.getRefundExpected().add(bookingRow.getRefundExpected()));
        routeRow.setFeeAmount(routeRow.getFeeAmount().add(bookingRow.getFeeAmount()));
        routeRow.setPaid(routeRow.isPaid() || bookingRow.isPaid());
        cancellationLogRepository.save(routeRow);
    }

    /**
     * One paid seat ended. fee is what the rider doesn't get back (null or 0 for a full refund); refundCardFee is the
     * card-fee rule the BOOKING_CANCELLED event carries. refundExpected is what payment-service says this cancellation
     * will send back (CancellationRefund.of - the fare left after any earlier ops refund, less the fee, plus the card fee
     * when it goes back), asked before the event goes out; the fare less the fee only when payment-service can't say.
     * A seat already in the log isn't written again - a booking only ends once - and null comes back.
     */
    @Transactional
    public CancellationLog recordBooking(Booking booking, RoutePost route, RouteStatus routeStatusBefore, BookingStatus statusBefore,
                                         Context context, BigDecimal fee, String parentId, boolean refundCardFee) {
        if (cancellationLogRepository.existsByBookingId(booking.getId())) {
            return null;
        }
        PaymentServiceClient.CancellationRefund split = paymentServiceClient.cancellationRefunds(List.of(
                new PaymentServiceClient.CancellationRefundQuery(booking.getId(), fee == null ? BigDecimal.ZERO : fee, refundCardFee)))
                .get(booking.getId());
        return recordBooking(booking, route, routeStatusBefore, statusBefore, context, fee, parentId,
                split == null ? null : split.refund());
    }

    /**
     * The same, when the caller already asked payment-service what this cancellation refunds (an ops cancel, a whole
     * trip) - refund null falls back to the fare less the fee.
     */
    @Transactional
    public CancellationLog recordBooking(Booking booking, RoutePost route, RouteStatus routeStatusBefore, BookingStatus statusBefore,
                                         Context context, BigDecimal fee, String parentId, BigDecimal refund) {
        if (cancellationLogRepository.existsByBookingId(booking.getId())) {
            return null;
        }
        Instant now = Instant.now();
        BigDecimal fare = nz(booking.getFareTotal());
        BigDecimal kept = fee == null ? BigDecimal.ZERO : fee.max(BigDecimal.ZERO).min(fare);
        return cancellationLogRepository.save(CancellationLog.builder()
                .kind(CancellationLog.Kind.BOOKING)
                .routePostId(route.getId())
                .bookingId(booking.getId())
                .parentId(parentId)
                .riderId(booking.getRiderId())
                .driverId(route.getDriverId())
                .source(context.source())
                .cancelledBy(context.cancelledBy())
                .actorId(context.actorId())
                .actorName(context.actorName())
                .reason(context.reasonOrDefault())
                .fault(context.faultOrDefault())
                .cancelledAt(now)
                .departureTime(route.getDepartureTime())
                .minutesBeforeDeparture(minutesBefore(route.getDepartureTime(), now))
                .routeStatusBefore(routeStatusBefore == null ? null : routeStatusBefore.name())
                .bookingStatusBefore(statusBefore == null ? null : statusBefore.name())
                .seatCount(booking.getSeatCount())
                .fareTotal(fare)
                .paid(true)
                .feeAmount(kept)
                .refundExpected(refund != null ? refund : fare.subtract(kept))
                .originLabel(route.getOriginLabel())
                .destinationLabel(route.getDestinationLabel())
                .build());
    }

    /**
     * Who ended this booking, from the log - its own row when it was paid, else the trip's ROUTE row when the whole trip
     * was called off (unpaid seats on a cancelled trip get no row of their own). Empty when the log has neither, which
     * is an unpaid hold let go: the caller falls back to the status.
     */
    @Transactional(readOnly = true)
    public Optional<CancelledBy> whoCancelled(Booking booking) {
        Optional<CancellationLog> row = cancellationLogRepository.findFirstByBookingIdAndKind(booking.getId(), CancellationLog.Kind.BOOKING);
        if (row.isEmpty() && booking.getStatus() == BookingStatus.CANCELLED_DRIVER) {
            row = cancellationLogRepository.findFirstByRoutePostIdAndKindOrderByCancelledAtDesc(booking.getRoutePostId(),
                    CancellationLog.Kind.ROUTE);
        }
        return row.map(CancelledBy::of);
    }

    static int minutesBefore(Instant departure, Instant at) {
        return (int) Math.floorDiv(Duration.between(at, departure).toSeconds(), 60);
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
