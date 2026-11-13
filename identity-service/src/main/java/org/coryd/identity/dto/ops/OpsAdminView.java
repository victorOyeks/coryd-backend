package org.coryd.identity.dto.ops;

import org.coryd.common.enums.OpsPermission;
import org.coryd.common.enums.OpsRole;

import java.time.Instant;
import java.util.Set;

/**
 * One ops admin for the console's Admin team screen. opsRole is the effective role (an admin with none stored is a
 * SUPER_ADMIN). invitedBy* are null for admins created before invites; invite* describe their latest invite, if any.
 * mfaEnabled: they sign in with a code from an authenticator app (phase 7).
 */
public record OpsAdminView(String userId, String firstName, String lastName, String fullName, String email, String phone,
                           OpsRole opsRole, String opsRoleLabel, Set<OpsPermission> permissions, OpsAdminStatus status,
                           Instant lastSeenAt, Instant invitedAt, String invitedById, String invitedByName,
                           Instant inviteExpiresAt, Instant deactivatedAt, String deactivationReason, Instant createdAt,
                           boolean mfaEnabled) {
}
