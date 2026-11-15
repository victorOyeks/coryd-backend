package org.coryd.notification.security;

import jakarta.servlet.http.HttpServletRequest;
import org.coryd.common.security.CoRydUserDetails;
import org.coryd.common.security.filter.InternalApiKeyFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Who may act on a user's bell feed, notification preferences and device tokens: the user themself (token subject equals
 * the userId in the path) or another CoRyd service (internal API key). Everyone else - another rider or driver, and ops
 * admins too, since the Ops Console never calls these endpoints - gets 403. They used to act on whatever id was in the URL,
 * so any signed-in user could read someone's feed, change their preferences, or register their own phone's FCM token on
 * someone else's account and receive that person's pushes.
 *
 * <p>403 rather than 401: the apps treat a 401 as "signed out".
 */
@Component
public class NotificationOwnership {

    public static final String NOT_YOURS = "You can only manage your own notifications";

    private final String internalApiKey;

    public NotificationOwnership(@Value("${services.internal.api-key}") String internalApiKey) {
        this.internalApiKey = internalApiKey;
    }

    /** A call from another CoRyd service, which may act for any user. */
    public boolean isService(HttpServletRequest request) {
        return InternalApiKeyFilter.hasValidKey(request, internalApiKey);
    }

    public void requireSelf(String userId, CoRydUserDetails caller, HttpServletRequest request) {
        if (isService(request)) {
            return;
        }
        if (caller == null || caller.getUserId() == null || !caller.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, NOT_YOURS);
        }
    }
}
