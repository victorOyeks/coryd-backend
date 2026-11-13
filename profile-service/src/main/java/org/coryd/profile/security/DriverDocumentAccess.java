package org.coryd.profile.security;

import jakarta.servlet.http.HttpServletRequest;
import org.coryd.common.enums.OpsPermission;
import org.coryd.common.enums.UserRole;
import org.coryd.common.security.CoRydUserDetails;
import org.coryd.common.security.OpsPermissions;
import org.coryd.common.security.filter.InternalApiKeyFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Who may read a driver's documents (licence, insurance, roadworthiness - personal data): the driver themself, another
 * CoRyd service (internal API key), or an ops admin whose role holds VERIFICATION_VIEW. Everyone else - another driver, any
 * rider - gets 403. It used to be readable by any signed-in user.
 *
 * <p>403 rather than the 401 older "only your own" checks in this codebase use: the apps treat a 401 as "signed out".
 */
@Component
public class DriverDocumentAccess {

    public static final String NOT_YOURS = "You can only view your own documents";

    private final String internalApiKey;

    public DriverDocumentAccess(@Value("${services.internal.api-key}") String internalApiKey) {
        this.internalApiKey = internalApiKey;
    }

    public void requireCanRead(String driverId, CoRydUserDetails caller, HttpServletRequest request) {
        if (InternalApiKeyFilter.hasValidKey(request, internalApiKey)) {
            return;
        }
        if (caller == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, NOT_YOURS);
        }
        if (caller.getActiveRole() == UserRole.OPS_ADMIN) {
            String refusal = OpsPermissions.refusal(caller, OpsPermission.VERIFICATION_VIEW);
            if (refusal != null) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, refusal);
            }
            return;
        }
        if (!driverId.equals(caller.getUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, NOT_YOURS);
        }
    }
}
