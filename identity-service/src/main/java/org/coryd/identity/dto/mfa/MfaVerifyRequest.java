package org.coryd.identity.dto.mfa;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** code: the 6 digits from the app, or an unused recovery code (xxxx-xxxx). */
public record MfaVerifyRequest(@NotBlank(message = "Sign in again") @Size(max = 200) String mfaToken,
                               @NotBlank(message = "Enter the 6-digit code from your app") @Size(max = 20) String code) {
}
