package org.coryd.identity.dto.mfa;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MfaEnrollSetupRequest(@NotBlank(message = "Sign in again") @Size(max = 200) String enrollToken) {
}
