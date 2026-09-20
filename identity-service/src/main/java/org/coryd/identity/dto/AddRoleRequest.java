package org.coryd.identity.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.coryd.common.enums.UserRole;

/** For an already-authenticated user self-attaching a new role - no OTP needed, the JWT already proves identity. */
public record AddRoleRequest(
        @NotNull UserRole role,
        /* Both of these hand out a fresh session, so the new refresh token has to stay bound to the
           same handset the old one was. Left out, switching role would quietly downgrade a bound
           session to an unbound one - a way around device binding that costs an attacker one call. */
        @Size(max = 100) String deviceId
) {
}
