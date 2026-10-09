package org.coryd.payment.dto;

import java.math.BigDecimal;
import java.time.Instant;

/** One row of a rider's payment history - a trip payment or a refund back to their card. */
public record RiderPaymentItem(
        String id,
        RiderPaymentType type,
        Instant occurredAt,
        BigDecimal amount,
        RiderPaymentStatus status,
        Card card,
        RefundKind refundKind,
        String bookingId,
        Route route
) {
    /** The card a payment was made with - null on refunds, and on payments from before cards were recorded. */
    public record Card(String cardType, String last4) {
    }

    /** Null if trip-service couldn't be reached. */
    public record Route(String routePostId, String originLabel, String destinationLabel, Instant departureTime) {
    }

    public RiderPaymentItem withRoute(Route enriched) {
        return new RiderPaymentItem(id, type, occurredAt, amount, status, card, refundKind, bookingId, enriched);
    }
}
