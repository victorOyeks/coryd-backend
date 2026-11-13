package org.coryd.ops.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * One row of the Cancellations Log. ROUTE rows are a whole trip (rider null, childBookingIds its paid seats, money
 * summed over them); BOOKING rows one seat. actorName is the ops admin, rider or driver who cancelled - null for the
 * system. minutesBeforeDeparture is negative once departure had passed. refund comes from payment-service.
 */
public record CancellationItem(
        String id,
        CancellationKind kind,
        String routePostId,
        String tripRef,
        String bookingId,
        String originLabel,
        String destinationLabel,
        Instant departureTime,
        Instant cancelledAt,
        int minutesBeforeDeparture,
        CancellationSource source,
        String actorId,
        String actorName,
        String reason,
        CancellationFault fault,
        RiderSummary rider,
        DriverSummary driver,
        Integer seatCount,
        BigDecimal fareTotal,
        boolean paid,
        BigDecimal feeAmount,
        RefundOutcome refund,
        int bookingsAffected,
        List<String> childBookingIds,
        String routeStatusBefore,
        String bookingStatusBefore,
        boolean backfilled
) {
}
