package org.coryd.payment.dto;

import java.math.BigDecimal;
import java.time.Instant;

/** One completed trip (or kept late-cancellation fee) on the earnings screen. routePostId links to the trip receipt. */
public record EarningsTripItem(
        String id,
        String bookingId,
        String routePostId,
        Instant occurredAt,
        BigDecimal grossFare,
        BigDecimal commissionAmount,
        BigDecimal netCredit,
        Rider rider
) {
    /** Name and photo are null if profile-service couldn't be reached. */
    public record Rider(String riderId, String firstName, String lastName, String photoUrl) {
    }
}
