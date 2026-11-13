package org.coryd.ops.dto;

import com.fasterxml.jackson.databind.JsonNode;
import org.coryd.common.enums.RiderDirectoryStatus;

import java.math.BigDecimal;

/**
 * A rider's profile in the Ops Console - everything in one call; see docs/ops-console/accounts.md section 3. suspension is
 * null unless the account is suspended; a part whose owning service couldn't be reached is null.
 */
public record RiderProfileView(
        DriverSummary rider,
        RiderDirectoryStatus status,
        DriverProfileView.Suspension suspension,
        DriverProfileView.Account account,
        Activity activity,
        String email,
        /** {identity: {status, submittedAt, decidedAt, rejectionReason}} - the identity (NIN) check. */
        JsonNode verification,
        JsonNode emergencyContacts,
        JsonNode suspensionHistory,
        JsonNode recentActions,
        JsonNode pendingDeletion,
        /** As stored - what PATCH /users/{id}/details edits; don't split the display name. */
        String firstName,
        String lastName
) {
    public record Activity(Long totalTrips, Double rating, Long ratingCount, BigDecimal totalSpent, Integer reliabilityPercent,
                           Long reportsFiled) {
    }
}
