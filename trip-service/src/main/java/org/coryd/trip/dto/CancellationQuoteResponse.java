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
        /**
         * Back to the card if they cancel now: fareTotal less feeAmount, plus the card fee when it goes back too
         * (cardFeeRefunded); 0 for a hold that was never paid.
         */
        BigDecimal refundAmount,
        /** When the fee starts to apply (departure less the window) - in the past once it applies; null when it never will (NOT_PAID, FREE_TIME_CHANGED). */
        Instant freeUntil,
        /** ISO 4217 - every amount above is in it. */
        String currency,
        /**
         * M10: the card fee the rider paid on top of the fare - 0 for an unpaid hold or a payment from before it was
         * recorded, null when payment-service couldn't be reached (refundAmount then counts only the fare).
         */
        BigDecimal cardFee,
        /** Whether that card fee comes back if they cancel now - only when the driver moved the trip (FREE_TIME_CHANGED). */
        boolean cardFeeRefunded,
        /** What the card was charged: fareTotal + cardFee (fareTotal alone when cardFee is unknown). */
        BigDecimal amountPaid
) {

    /** The same quote with the card fee the rider paid filled in. */
    public CancellationQuoteResponse withCardFee(BigDecimal fee) {
        if (fee == null || fee.signum() <= 0) {
            return new CancellationQuoteResponse(bookingId, reason, feeApplies, feePercent, freeCancellationWindowHours, fareTotal,
                    feeAmount, refundAmount, freeUntil, currency, fee == null ? null : java.math.BigDecimal.ZERO, false, fareTotal);
        }
        boolean back = reason == Reason.FREE_TIME_CHANGED;
        return new CancellationQuoteResponse(bookingId, reason, feeApplies, feePercent, freeCancellationWindowHours, fareTotal,
                feeAmount, back ? refundAmount.add(fee) : refundAmount, freeUntil, currency, fee, back, fareTotal.add(fee));
    }

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
