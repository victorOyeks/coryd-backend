package org.coryd.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResetPasswordRequest(
        @NotBlank String phone,
        @NotBlank String code,
        // max 72: BCrypt's own input limit - without this cap, Spring Security's
        // encoder throws IllegalArgumentException (an unhandled 500) instead of
        // a clean 400 for an overly long password.
        @NotBlank @Size(min = 8, max = 72) String newPassword,
        @NotBlank String confirmPassword
) {
}
