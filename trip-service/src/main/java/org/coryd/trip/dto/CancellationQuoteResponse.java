package org.coryd.trip.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * What the booking's rider cancelling right now would cost - worked out by the same code POST /bookings/{id}/cancel
 * runs, so the app never states the fee or the window itself (ops can change both at runtime).
 */
public record CancellationQuoteResponse(
        String bookingId,
        Reason reason,
        /** True when cancelling now keeps the late-cancellation fee back. */
        boolean feeApplies,
        /** The late-cancellation fee in effect, as a percentage of the fare (25 = 25%) - whether or not it applies now. */
        BigDecimal feePercent,
        /** The fee window in effect: cancelling less than this many hours before departure costs feePercent. */
        int freeCancellationWindowHours,
        /** What the rider paid for the seat(s). */
        BigDecimal fareTotal,
        /** Kept back if they cancel now - 0 unless feeApplies. */
        BigDecimal feeAmount,
        /** Back to the card if they cancel now - fareTotal less feeAmount; 0 for a hold that was never paid. */
        BigDecimal refundAmount,
        /** When the fee starts to apply (departure less the window) - in the past once it applies; null when it never will (NOT_PAID, FREE_TIME_CHANGED). */
        Instant freeUntil,
        /** ISO 4217 - every amount above is in it. */
        String currency
) {

    public static final String CURRENCY = "NGN";

    public enum Reason {
        /** Inside the window: feeAmount is kept, the rest goes back to the card. */
        LATE_CANCELLATION,
        /** Before the window (freeUntil is still ahead): a full refund. */
        FREE_BEFORE_WINDOW,
        /** The driver moved the trip far enough from what was booked that cancelling is free, whatever the time. */
        FREE_TIME_CHANGED,
        /** A seat held or reserved but never paid for: cancelling just lets it go - nothing to refund. */
        NOT_PAID
    }
}
