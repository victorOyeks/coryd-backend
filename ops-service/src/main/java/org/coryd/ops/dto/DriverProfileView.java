package org.coryd.ops.dto;

import com.fasterxml.jackson.databind.JsonNode;
import org.coryd.common.enums.DriverDirectoryStatus;
import org.coryd.common.enums.VerificationStatus;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A driver's profile in the Ops Console - everything in one call; see docs/ops-console/accounts.md section 3. suspension is
 * null unless the account is suspended. Performance numbers that couldn't be loaded are null; so is any part whose owning
 * service couldn't be reached (the rest still loads).
 */
public record DriverProfileView(
        DriverSummary driver,
        DriverDirectoryStatus status,
        VerificationStatus verificationStatus,
        Suspension suspension,
        DriverDirectoryItem.Vehicle vehicle,
        Performance performance,
        Account account,
        String email,
        /** {status, verifiedSince, items[], licenceAttempts, identity} - see accounts.md. */
        JsonNode verification,
        /** Every vehicle the driver registered: [{id, make, model, year, color, plateNumber, seatCapacity, hasAC, status, rejectionReason, primary}]. */
        JsonNode vehicles,
        /** [{id, name, phone, primary}] */
        JsonNode emergencyContacts,
        /** Every suspension, oldest first: [{suspendedAt, suspendedBy, reason, reinstatedAt, reinstatedBy, reinstateReason}]. */
        JsonNode suspensionHistory,
        /** The latest 10 entries of the account history - the rest from GET /users/{id}/account-history. */
        JsonNode recentActions,
        /** A deletion the person asked for that was refused - {requestId, lastAttemptAt, blockers}; null if none. */
        JsonNode pendingDeletion,
        /** As stored - what PATCH /users/{id}/details edits; don't split the display name. */
        String firstName,
        String lastName
) {
    /** suspendedBy: {id, name} of the admin - null for suspensions made before this was recorded. */
    public record Suspension(Instant suspendedAt, String reason, JsonNode suspendedBy) {

        public Suspension(Instant suspendedAt, String reason) {
            this(suspendedAt, reason, null);
        }
    }

    public record Performance(Long totalTrips, Double rating, Long ratingCount, BigDecimal totalEarnings, BigDecimal grossEarnings,
                              Integer reliabilityPercent, Long reportsFiled) {
    }

    /**
     * status: ACTIVE or SUSPENDED. lastSeenAt: the last sign-in or session refresh on any device. sessionsValidAfter: sessions
     * started before this were ended by ops (null if never). devices: [{deviceId, deviceName, platform, lastSeenAt, signedIn, biometricEnabled}].
     */
    public record Account(String ref, Instant joinedAt, String status, JsonNode roles, Instant phoneVerifiedAt, Instant lastSeenAt,
                          Instant sessionsValidAfter, JsonNode devices) {
    }
}
