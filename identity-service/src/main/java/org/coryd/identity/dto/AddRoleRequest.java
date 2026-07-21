package org.coryd.identity.dto;

import jakarta.validation.constraints.NotNull;
import org.coryd.common.enums.UserRole;

/** For an already-authenticated user self-attaching a new role - no OTP needed, the JWT already proves identity. */
public record AddRoleRequest(
        @NotNull UserRole role
) {
}
