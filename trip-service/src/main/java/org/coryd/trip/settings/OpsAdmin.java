package org.coryd.trip.settings;

import org.coryd.common.enums.UserRole;
import org.coryd.common.security.CoRydUserDetails;
import org.springframework.security.access.AccessDeniedException;

/** The ops admin behind a phase 6 request - read from their own forwarded token, never from the body. Anyone else gets a 403. */
public record OpsAdmin(String id, String name) {

    public static OpsAdmin of(CoRydUserDetails principal) {
        if (principal == null || principal.getActiveRole() != UserRole.OPS_ADMIN) {
            throw new AccessDeniedException("Only an ops admin can do this");
        }
        String name = principal.getFullName();
        return new OpsAdmin(principal.getUserId(), name == null || name.isBlank() ? principal.getUserId() : name.trim());
    }
}
