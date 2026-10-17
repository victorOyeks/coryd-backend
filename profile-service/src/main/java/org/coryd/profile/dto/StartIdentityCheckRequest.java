package org.coryd.profile.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** consent must be true - the person agreed to their NIN and selfie being checked with NIMC through Smile ID. */
public record StartIdentityCheckRequest(
        @NotBlank @Pattern(regexp = "^\\d{11}$", message = "NIN must be 11 digits") String nin,
        @NotNull @AssertTrue(message = "Agree to the identity check to continue") Boolean consent
) {
}
