package org.coryd.notification.broadcast;

import org.coryd.common.enums.UserRole;
import org.coryd.common.security.CoRydUserDetails;
import org.springframework.security.access.AccessDeniedException;

/** The ops admin behind a request - from their own forwarded token. Anyone else gets a 403. */
public record OpsAdmin(String id, String name) {

    public static OpsAdmin of(CoRydUserDetails principal) {
        if (principal == null || principal.getActiveRole() != UserRole.OPS_ADMIN) {
            throw new AccessDeniedException("Only an ops admin can do this");
        }
        String name = principal.getFullName();
        return new OpsAdmin(principal.getUserId(), name == null || name.isBlank() ? principal.getUserId() : name.trim());
    }
}
