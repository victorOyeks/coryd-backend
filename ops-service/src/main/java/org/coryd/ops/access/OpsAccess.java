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

    /** Allowed when the role has any one of these; none listed means super admins only. Refusal names the first. */
    public static void requireAny(CoRydUserDetails admin, java.util.Set<OpsPermission> permissions) {
        if (permissions.size() > 1) {
            OpsRole role = role(admin);
            if (role != null && permissions.stream().anyMatch(role::can)) {
                return;
            }
            if (role != null) {
                // today only the reveal (POST /users/{id}/reveal) is open to more than one permission
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Your ops role (" + role.name()
                        + ") can't reveal ID numbers or dates of birth - ask a verification or support admin");
            }
        }
        require(admin, permissions.isEmpty() ? OpsAccessPolicy.SUPER_ADMIN_ONLY : permissions.iterator().next());
    }

    /** 403 "Your ops role (FINANCE) can't cancel or resolve trips and bookings." unless allowed. */
    public static void require(CoRydUserDetails admin, OpsPermission permission) {
        String refusal = OpsPermissions.refusal(admin, permission);
        if (refusal != null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, refusal);
        }
    }
}
