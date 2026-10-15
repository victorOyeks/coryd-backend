package org.coryd.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.coryd.common.enums.HeardAboutUs;
import org.coryd.common.enums.UserRole;

public record SignupRequest(
        // Deliberately loose - PhoneNumbers.normalize() canonicalises the +234/234/0/bare-10-digit
        // forms riders actually type, so this only rejects input that is not a phone number at all.
        @NotBlank @Pattern(regexp = "^\\+?[0-9][0-9 ()-]{8,19}$") String phone,
        UserRole role,
        /** Optional "How did you hear about us?" answer. */
        HeardAboutUs heardAboutUs,
        /** Only kept when heardAboutUs is OTHER. */
        @Size(max = 100) String heardAboutUsOther
) {
}
