package org.coryd.identity.dto;

import org.coryd.common.enums.AccountDeletionStatus;

import java.time.Instant;
import java.util.List;

/**
 * An account deletion request, for ops (read-only). requestedBy / completedBy are "SELF" or the ops admin's user id.
 * firstName / lastName / phone are filled while the account still exists (PENDING); once it's deleted only the user id,
 * roles and the phone's last four digits remain.
 */
public record AccountDeletionView(
        String id,
        String userId,
        AccountDeletionStatus status,
        List<String> roles,
        String phoneLast4,
        String firstName,
        String lastName,
        String phone,
        Instant joinedAt,
        String requestedBy,
        Instant requestedAt,
        int attempts,
        Instant lastAttemptAt,
        List<String> blockers,
        Instant completedAt,
        String completedBy
) {
}
