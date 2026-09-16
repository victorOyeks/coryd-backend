package org.coryd.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.coryd.common.enums.UserRole;

public record SignupRequest(
        // Deliberately loose - PhoneNumbers.normalize() canonicalises the +234/234/0/bare-10-digit
        // forms riders actually type, so this only rejects input that is not a phone number at all.
        @NotBlank @Pattern(regexp = "^\\+?[0-9][0-9 ()-]{8,19}$") String phone,
        UserRole role
) {
}
