package org.coryd.common.security;

/** Per-service lists of paths only ever meant to be called by another CoRyd service, never a mobile client directly - gated by InternalApiKeyFilter on top of the normal JWT check. */
public final class InternalOnlyPaths {

    private InternalOnlyPaths() {
    }

    public static final String[] PROFILE_SERVICE = {
            "/drivers/batch", "/drivers/*", "/vehicles/batch", "/vehicles/*",
            "/riders/batch", "/riders/*/emergency-contacts"
    };

    public static final String[] IDENTITY_SERVICE = {
            "/users/*/contact"
    };

    /** Dev-only reset tooling, called by trip-service. Only reachable when coryd.dev-tools.enabled is true. */
    public static final String[] PAYMENT_SERVICE = {
            "/dev/**"
    };
}
