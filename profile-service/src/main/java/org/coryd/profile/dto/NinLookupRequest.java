package org.coryd.profile.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** consent must be true - the driver agreed to their NIN being looked up with NIMC through Smile ID. */
public record NinLookupRequest(
        @NotBlank @Pattern(regexp = "^\\d{11}$", message = "NIN must be 11 digits") String nin,
        @NotNull @AssertTrue(message = "Agree to the NIN check to continue") Boolean consent
) {
}
