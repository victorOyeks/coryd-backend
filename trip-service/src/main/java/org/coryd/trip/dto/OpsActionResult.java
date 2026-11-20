package org.coryd.trip.dto;

import org.coryd.trip.client.PaymentServiceClient;

import java.math.BigDecimal;
import java.util.List;

/**
 * What an ops action did. routeStatus/bookingStatus are before and after; refunds lists each paid seat that will be
 * refunded (the refund itself is payment-service's, from the BOOKING_CANCELLED each one sent); completed and noShows
 * are seats a stuck-trip completion settled.
 *
 * Refund.refund is what payment-service will send back to the card - asked of payment-service before the cancellation
 * went out, so it includes the card fee when that goes back and leaves out anything ops already refunded (B19).
 * refundEstimated: payment-service couldn't be reached, so refund is only the fare less the fee. cardFeeRefund: the part of
 * refund that is the card fee going back (0 when it stays with the payment provider; null when refundEstimated).
 * The cancel preview (OpsCancelPreview) returns the same rows, worked out the same way, before anything is cancelled.
 */
public record OpsActionResult(
        String action,
        String auditId,
        String routePostId,
        String bookingId,
        String statusBefore,
        String statusAfter,
        List<Refund> refunds,
        List<String> completed,
        List<String> noShows
) {
    public record Refund(String bookingId, String riderId, BigDecimal fareTotal, BigDecimal fee, BigDecimal refund,
                         boolean refundEstimated, BigDecimal cardFeeRefund) {

        /** payment-service's split when it answered; else the fare less the fee, marked refundEstimated. */
        public static Refund of(String bookingId, String riderId, BigDecimal fareTotal, BigDecimal fee,
                                PaymentServiceClient.CancellationRefund split) {
            BigDecimal fare = fareTotal == null ? BigDecimal.ZERO : fareTotal;
            BigDecimal kept = fee == null ? BigDecimal.ZERO : fee;
            if (split == null) {
                return new Refund(bookingId, riderId, fare, kept, fare.subtract(kept).max(BigDecimal.ZERO), true, null);
            }
            return new Refund(bookingId, riderId, fare, kept, split.refund(), false, split.cardFeeBackOrZero());
        }
    }
}
