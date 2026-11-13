package org.coryd.identity.dto.ops;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Resend / revoke an invite, deactivate / reactivate an admin - from ops-service, with the acting super admin. */
public record OpsTeamActionRequest(@Size(max = 500) String reason, @NotBlank String opsUserId, String opsUserName) {
}
