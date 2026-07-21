package org.coryd.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.coryd.common.enums.UserRole;

/** Step 2 of adding a role when the caller isn't currently logged in: confirm the OTP sent after the password challenge. */
public record RoleConfirmRequest(
        @NotBlank String phone,
        @NotBlank String code,
        @NotNull UserRole role
) {
}
