package org.coryd.profile.dto;

import java.util.List;

/** One row in the ops review queue - a driver with at least one item still awaiting a decision,
 * and which item(s) specifically. Click into GET /drivers/{driverId}/verification-status for the
 * full detail (urls, rejection reasons, etc.) needed to actually make the call. */
public record PendingVerificationItem(
        String driverId,
        String legalName,
        List<ReviewableItem> pendingItems
) {
}
