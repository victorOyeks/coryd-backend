package org.coryd.identity.dto;

import jakarta.validation.constraints.NotBlank;
import org.coryd.common.enums.UserRole;

public record LoginRequest(
        @NotBlank String phone,
        @NotBlank String password,
        // which role/app context to log into - defaults to RIDER so existing
        // single-app clients that don't send this keep working unchanged
        UserRole role
) {
    public UserRole roleOrDefault() {
        return role == null ? UserRole.RIDER : role;
    }
}
