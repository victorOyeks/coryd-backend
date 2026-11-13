package org.coryd.identity.dto.ops;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.coryd.common.enums.OpsRole;

/** POST /internal/ops-admins/invites - from ops-service, with the inviting super admin. */
public record InviteOpsAdminRequest(
        @NotBlank(message = "Enter their email address") @Email(message = "Enter a valid email address") @Size(max = 254) String email,
        @NotBlank(message = "Enter their first name") @Size(max = 100) String firstName,
        @NotBlank(message = "Enter their last name") @Size(max = 100) String lastName,
        @NotNull(message = "Choose their ops role") OpsRole opsRole,
        @NotBlank String opsUserId,
        String opsUserName) {
}
