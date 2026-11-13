package org.coryd.identity.dto.ops;

import org.coryd.common.enums.OpsRole;

import java.time.Instant;

/** GET /auth/ops-invite?token= - what the set-password page shows before the admin chooses a password. */
public record OpsInvitePreview(String email, String firstName, String lastName, OpsRole opsRole, String opsRoleLabel,
                               String invitedByName, Instant expiresAt) {
}
