package org.coryd.payment.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One row of a driver's statement. type decides which fields are set:
 * TRIP_EARNING - amount (net), grossFare, commission, bookingId, route, rider;
 * PAYOUT - amount, payoutStatus, reference (Paystack settlement id);
 * owed-money types - amount is the signed change to what's owed, owedAfter the balance after it;
 * OVERPAYMENT_REFUNDED - amount.
 */
public record DriverStatementItem(
        String id,
        DriverStatementType type,
        Instant occurredAt,
        BigDecimal amount,
        BigDecimal grossFare,
        BigDecimal commission,
        BigDecimal owedAfter,
        SettlementStatus payoutStatus,
        String bookingId,
        String reference,
        String note,
        Route route,
        Rider rider
) {
    /** Null if trip-service couldn't be reached. */
    public record Route(String routePostId, String originLabel, String destinationLabel, Instant departureTime) {
    }

    /** Name and photo are null if profile-service couldn't be reached. */
    public record Rider(String riderId, String firstName, String lastName, String photoUrl) {
    }

    public DriverStatementItem with(Route enrichedRoute, Rider enrichedRider) {
        return new DriverStatementItem(id, type, occurredAt, amount, grossFare, commission, owedAfter, payoutStatus,
                bookingId, reference, note, enrichedRoute, enrichedRider);
    }
}
