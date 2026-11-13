package org.coryd.trip.dto;

import org.coryd.trip.entity.CancellationFault;
import org.coryd.trip.entity.CancellationLog;
import org.coryd.trip.entity.CancellationSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * One Cancellations Log row as trip-service knows it - ops-service adds names and the refund's actual state from
 * payment-service. childBookingIds: for a ROUTE row, the paid seats it ended (each is its own BOOKING row too).
 */
public record OpsCancellationInfo(
        String id,
        CancellationLog.Kind kind,
        String routePostId,
        String bookingId,
        String parentId,
        String riderId,
        String driverId,
        CancellationSource source,
        String cancelledBy,
        String actorId,
        String actorName,
        String reason,
        CancellationFault fault,
        Instant cancelledAt,
        Instant departureTime,
        int minutesBeforeDeparture,
        String routeStatusBefore,
        String bookingStatusBefore,
        Integer seatCount,
        BigDecimal fareTotal,
        boolean paid,
        BigDecimal feeAmount,
        BigDecimal refundExpected,
        int bookingsAffected,
        String originLabel,
        String destinationLabel,
        boolean backfilled,
        List<String> childBookingIds
) {
    public static OpsCancellationInfo of(CancellationLog c, List<String> childBookingIds) {
        return new OpsCancellationInfo(c.getId(), c.getKind(), c.getRoutePostId(), c.getBookingId(), c.getParentId(), c.getRiderId(),
                c.getDriverId(), c.getSource(), c.getCancelledBy(), c.getActorId(), c.getActorName(), c.getReason(), c.getFault(),
                c.getCancelledAt(), c.getDepartureTime(), c.getMinutesBeforeDeparture(), c.getRouteStatusBefore(),
                c.getBookingStatusBefore(), c.getSeatCount(), c.getFareTotal(), c.isPaid(), c.getFeeAmount(), c.getRefundExpected(),
                c.getBookingsAffected(), c.getOriginLabel(), c.getDestinationLabel(), c.isBackfilled(),
                childBookingIds == null ? List.of() : childBookingIds);
    }
}
