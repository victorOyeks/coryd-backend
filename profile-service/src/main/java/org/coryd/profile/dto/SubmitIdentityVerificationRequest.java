package org.coryd.profile.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** selfieUrl/selfiePublicId come from POST /media/upload; consent must be true - the rider agreed to the NIMC check. */
public record SubmitIdentityVerificationRequest(
        @NotBlank @Pattern(regexp = "^\\d{11}$", message = "NIN must be 11 digits") String nin,
        @NotBlank String selfieUrl,
        @NotBlank String selfiePublicId,
        @NotNull @AssertTrue(message = "Agree to the identity check to continue") Boolean consent
) {
}
