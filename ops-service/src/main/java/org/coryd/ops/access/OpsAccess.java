package org.coryd.ops.access;

import org.coryd.common.enums.OpsPermission;
import org.coryd.common.enums.OpsRole;
import org.coryd.common.security.CoRydUserDetails;
import org.coryd.common.security.OpsPermissions;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * An ops admin's effective role from their token - no claim means SUPER_ADMIN, an unknown one means no access. The check
 * itself is common's {@link OpsPermissions}, shared with the owning services' OpsPermissionFilter.
 */
public final class OpsAccess {

    private OpsAccess() {
    }

    /** The role the token grants, or null when it names a role this service doesn't know (refused). */
    public static OpsRole role(CoRydUserDetails admin) {
        return OpsPermissions.role(admin);
    }

    /** 403 "Your ops role (FINANCE) can't cancel or resolve trips and bookings." unless allowed. */
    public static void require(CoRydUserDetails admin, OpsPermission permission) {
        String refusal = OpsPermissions.refusal(admin, permission);
        if (refusal != null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, refusal);
        }
    }
}
