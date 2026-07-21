package org.coryd.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.coryd.common.enums.UserRole;

public record SignupRequest(
        @NotBlank @Pattern(regexp = "^\\+?[0-9]{10,15}$") String phone,
        UserRole role
) {
}
