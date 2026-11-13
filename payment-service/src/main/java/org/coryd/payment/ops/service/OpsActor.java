package org.coryd.payment.ops.service;

import org.coryd.common.enums.UserRole;
import org.coryd.common.security.CoRydUserDetails;
import org.springframework.security.access.AccessDeniedException;

/** The ops admin behind a request - read from their own forwarded token, never from the request body. */
public record OpsActor(String id, String name) {

    /** Anyone else gets a 403 - payment-service's /internal paths are only key-gated with the dev tools on. */
    public static OpsActor of(CoRydUserDetails principal) {
        if (principal == null || principal.getActiveRole() != UserRole.OPS_ADMIN) {
            throw new AccessDeniedException("Only an ops admin can do this");
        }
        String name = principal.getFullName();
        return new OpsActor(principal.getUserId(), name == null || name.isBlank() ? null : name);
    }
}
