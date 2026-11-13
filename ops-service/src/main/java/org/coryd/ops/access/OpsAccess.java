package org.coryd.ops.access;

import org.coryd.common.enums.OpsPermission;
import org.coryd.common.enums.OpsRole;
import org.coryd.common.security.CoRydUserDetails;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** An ops admin's effective role from their token - no claim means SUPER_ADMIN, an unknown one means no access. */
public final class OpsAccess {

    private OpsAccess() {
    }

    /** The role the token grants, or null when it names a role this service doesn't know (refused). */
    public static OpsRole role(CoRydUserDetails admin) {
        if (admin == null || admin.getOpsRole() == null || admin.getOpsRole().isBlank()) {
            return OpsRole.SUPER_ADMIN;
        }
        try {
            return OpsRole.parse(admin.getOpsRole());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 403 "Your ops role (FINANCE) can't cancel or resolve trips and bookings." unless allowed. */
    public static void require(CoRydUserDetails admin, OpsPermission permission) {
        OpsRole role = role(admin);
        if (role == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Your ops role (" + admin.getOpsRole() + ") isn't recognised - sign out and sign in again");
        }
        if (permission == OpsAccessPolicy.SUPER_ADMIN_ONLY) {
            if (role != OpsRole.SUPER_ADMIN) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Your ops role (" + role.name() + ") can't use this part of the console - it's for super admins");
            }
            return;
        }
        if (!role.can(permission)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Your ops role (" + role.name() + ") can't " + permission.refusal());
        }
    }
}
