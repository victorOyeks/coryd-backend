package org.coryd.identity.dto.mfa;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MfaEnrollConfirmRequest(@NotBlank(message = "Sign in again") @Size(max = 200) String enrollToken,
                                      @NotBlank(message = "Enter the 6-digit code from your app") @Size(max = 20) String code) {
}
