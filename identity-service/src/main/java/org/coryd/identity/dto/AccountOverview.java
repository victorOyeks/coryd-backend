package org.coryd.identity.dto;

import org.coryd.common.enums.DevicePlatform;
import org.coryd.common.enums.UserRole;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Everything identity-service knows about an account, for the ops console's profile screens. status is ACTIVE or SUSPENDED.
 * lastSeenAt is the last sign-in or session refresh on any device. sessionsValidAfter: sessions started before this were
 * ended by ops (null if never).
 */
public record AccountOverview(
        String userId,
        String phone,
        String firstName,
        String lastName,
        Set<UserRole> roles,
        String status,
        Instant joinedAt,
        Instant phoneVerifiedAt,
        Instant lastSeenAt,
        Instant sessionsValidAfter,
        List<Device> devices,
        Suspension suspension,
        List<SuspensionPeriod> suspensionHistory,
        PendingDeletion pendingDeletion
) {
    /** signedIn is false once the device was signed out (by the user, or by ops signing the account out everywhere). */
    public record Device(String deviceId, String deviceName, DevicePlatform platform, Instant lastSeenAt, boolean signedIn,
                         boolean biometricEnabled) {
    }

    /** The current suspension. suspendedBy is null for a suspension made before this was recorded. */
    public record Suspension(Instant suspendedAt, String reason, OpsActor suspendedBy) {
    }

    /** One suspension, oldest first; reinstated* are null while it's still in force. */
    public record SuspensionPeriod(Instant suspendedAt, OpsActor suspendedBy, String reason, Instant reinstatedAt,
                                   OpsActor reinstatedBy, String reinstateReason) {
    }

    /** A deletion the person (or ops) asked for that was refused - see GET /account-deletions/{id}. */
    public record PendingDeletion(String requestId, Instant lastAttemptAt, List<String> blockers) {
    }
}
