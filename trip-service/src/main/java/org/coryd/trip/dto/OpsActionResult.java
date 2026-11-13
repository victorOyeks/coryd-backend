package org.coryd.trip.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * What an ops action did. routeStatus/bookingStatus are before and after; refunds lists each paid seat that will be
 * refunded (the refund itself is payment-service's, from the BOOKING_CANCELLED each one sent); completed and noShows
 * are seats a stuck-trip completion settled.
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
    public record Refund(String bookingId, String riderId, BigDecimal fareTotal, BigDecimal fee, BigDecimal refund) {
    }
}
