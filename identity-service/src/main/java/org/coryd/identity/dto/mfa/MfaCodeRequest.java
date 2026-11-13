package org.coryd.identity.dto.mfa;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** A code from the app (or, where allowed, a recovery code) - from ops-service for the signed-in admin. */
public record MfaCodeRequest(@NotBlank(message = "Enter the 6-digit code from your app") @Size(max = 20) String code,
                             @Size(max = 500) String reason) {
}
