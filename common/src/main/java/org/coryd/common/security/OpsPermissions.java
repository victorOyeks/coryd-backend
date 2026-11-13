package org.coryd.common.security;

import org.coryd.common.enums.OpsPermission;
import org.coryd.common.enums.OpsRole;
import org.coryd.common.enums.UserRole;

/**
 * The one ops-role check every service uses: ops-service's OpsAccessPolicy and the owning services' OpsPermissionFilter.
 * An OPS_ADMIN token without the opsRole claim (from before ops roles, or a service's system token) counts as SUPER_ADMIN;
 * an unknown value is refused.
 */
public final class OpsPermissions {

    /** Pass as the permission when only a super admin may go further (no other role holds it). */
    public static final OpsPermission SUPER_ADMIN_ONLY = null;

    private OpsPermissions() {
    }

    /** The role the token grants, or null when it names a role this build doesn't know. */
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

    /**
     * Null when this OPS_ADMIN principal may use something needing the permission ({@link #SUPER_ADMIN_ONLY}: super admins
     * only); otherwise the 403 message, e.g. "Your ops role (FINANCE) can't decide identity checks or driver documents".
     * Principals that aren't ops admins get null - their own rules (self only, etc.) apply elsewhere.
     */
    public static String refusal(CoRydUserDetails admin, OpsPermission permission) {
        if (admin == null || admin.getActiveRole() != UserRole.OPS_ADMIN) {
            return null;
        }
        OpsRole role = role(admin);
        if (role == null) {
            return "Your ops role (" + admin.getOpsRole() + ") isn't recognised - sign out and sign in again";
        }
        if (permission == SUPER_ADMIN_ONLY) {
            return role == OpsRole.SUPER_ADMIN ? null
                    : "Your ops role (" + role.name() + ") can't use this part of the console - it's for super admins";
        }
        return role.can(permission) ? null : "Your ops role (" + role.name() + ") can't " + permission.refusal();
    }
}
