package org.coryd.ops.dto;

import org.coryd.common.enums.BookingStatus;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One trip in a rider's Trip History. fareTotal is what the booking cost; payment is what was charged and refunded
 * (null if payment-service couldn't be reached or has no record).
 */
public record RiderTripItem(
        String bookingId,
        String ref,
        String routePostId,
        BookingStatus status,
        String originLabel,
        String destinationLabel,
        Instant departureTime,
        int seatCount,
        BigDecimal fareTotal,
        Driver driver,
        Payment payment
) {
    public record Driver(String id, String name, Double rating, Long ratingCount) {
    }

    /** status: PAID, PENDING, PROCESSING or FAILED. netPaid = amountPaid - refunded - refundPending. */
    public record Payment(String status, String method, String cardType, String cardLast4, String reference, Instant paidAt,
                          BigDecimal amountPaid, BigDecimal refunded, BigDecimal refundPending, BigDecimal netPaid) {
    }
}
