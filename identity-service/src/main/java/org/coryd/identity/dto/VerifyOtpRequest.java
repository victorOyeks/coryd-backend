package org.coryd.identity.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

public record VerifyOtpRequest(
        @NotBlank String phone,
        @NotBlank String code,
        /* Optional - binds the session issued here to this handset. Worth sending even on signup:
           it is the first session the app will store, and on a biometric-enabled client it is the
           one the fingerprint will later unlock. */
        @Valid DeviceInfo device
) {
}
