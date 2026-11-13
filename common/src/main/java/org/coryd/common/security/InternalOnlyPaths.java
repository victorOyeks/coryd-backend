package org.coryd.common.security;

/**
 * Per-service internal-only path prefix, gated by InternalApiKeyFilter on top of the normal
 * JWT check - never called by a mobile client directly, only by another CoRyd service.
 * Every internal-only endpoint lives under /internal/** (its own dedicated controller, e.g.
 * InternalDriverController) rather than sharing a path with a public endpoint - a single
 * fixed prefix per service instead of a per-resource glob list that could accidentally
 * overlap with a public path (as the old "/vehicles/*" pattern did with the public
 * PUT /vehicles/{vehicleId} update endpoint, both matching the same path regardless of
 * HTTP method).
 */
public final class InternalOnlyPaths {

    private InternalOnlyPaths() {
    }

    public static final String[] PROFILE_SERVICE = {"/internal/**"};

    public static final String[] IDENTITY_SERVICE = {"/internal/**"};

    /** Platform stats read by notification-service's report jobs. */
    public static final String[] TRIP_SERVICE = {"/internal/**"};

    /** Also covers the dev-only reset tooling (/internal/dev/**), called by trip-service - only reachable when coryd.dev-tools.enabled is true. */
    public static final String[] PAYMENT_SERVICE = {"/internal/**"};

    /** Delivery records read by ops-service (which channel an SOS alert actually went out on). */
    public static final String[] NOTIFICATION_SERVICE = {"/internal/**"};
}
