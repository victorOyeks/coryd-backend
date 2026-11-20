package org.coryd.trip.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * What an ops action did. routeStatus/bookingStatus are before and after; refunds lists each paid seat that will be
 * refunded (the refund itself is payment-service's, from the BOOKING_CANCELLED each one sent); completed and noShows
 * are seats a stuck-trip completion settled.
 *
 * Refund.refund is what payment-service will send back to the card - asked of payment-service before the cancellation
 * went out, so it includes the card fee when that goes back and leaves out anything ops already refunded (B19).
 * refundEstimated: payment-service couldn't be reached, so refund is only the fare less the fee.
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
                         boolean refundEstimated) {
    }
}
