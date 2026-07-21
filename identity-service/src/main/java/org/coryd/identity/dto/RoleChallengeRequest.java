package org.coryd.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.coryd.common.enums.UserRole;

/** Step 1 of adding a role when the caller isn't currently logged in: prove you own the account with its password, then get an OTP. */
public record RoleChallengeRequest(
        @NotBlank String phone,
        @NotBlank String password,
        @NotNull UserRole role
) {
}
