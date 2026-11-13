package org.coryd.trip.service;

import lombok.RequiredArgsConstructor;
import org.coryd.common.enums.BookingStatus;
import org.coryd.common.enums.RouteStatus;
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

/**
 * Writes the Cancellations Log - called from every place a trip or a paid seat ends, inside that cancellation's own
 * transaction, so the log can't say something happened that was rolled back.
 */
@Service
@RequiredArgsConstructor
public class CancellationLogService {

    private final CancellationLogRepository cancellationLogRepository;

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
     * One paid seat ended. fee is what the rider doesn't get back (null or 0 for a full refund). A seat already in the
     * log isn't written again - a booking only ends once - and null comes back.
     */
    @Transactional
    public CancellationLog recordBooking(Booking booking, RoutePost route, RouteStatus routeStatusBefore, BookingStatus statusBefore,
                                         Context context, BigDecimal fee, String parentId) {
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
                .refundExpected(fare.subtract(kept))
                .originLabel(route.getOriginLabel())
                .destinationLabel(route.getDestinationLabel())
                .build());
    }

    static int minutesBefore(Instant departure, Instant at) {
        return (int) Math.floorDiv(Duration.between(at, departure).toSeconds(), 60);
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
