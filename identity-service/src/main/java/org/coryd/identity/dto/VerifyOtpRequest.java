package org.coryd.identity.dto;

import jakarta.validation.constraints.NotBlank;

public record VerifyOtpRequest(
        @NotBlank String phone,
        @NotBlank String code
) {
}
