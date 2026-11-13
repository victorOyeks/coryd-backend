package org.coryd.common.enums;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * An ops admin's role inside the console - a sub-role of {@link UserRole#OPS_ADMIN}, carried on the access token as the
 * additive "opsRole" claim. An OPS_ADMIN token without the claim (tokens from before roles existed, service-to-service
 * system tokens) counts as {@link #SUPER_ADMIN}, which is what every ops admin was before.
 */
public enum OpsRole {
    /** Everything, including the admin team and the audit log. */
    SUPER_ADMIN("Super admin", EnumSet.allOf(OpsPermission.class)),
    /** Identity checks, the verification queue, documents and their expiry; directories read-only. */
    VERIFICATION("Verification", EnumSet.of(OpsPermission.VERIFICATION_VIEW, OpsPermission.VERIFICATION_DECIDE,
            OpsPermission.DIRECTORY_VIEW, OpsPermission.CONSOLE_BASICS)),
    /** Money: transactions, refunds, approvals, payouts, earnings and debt, finance dashboard, pricing; trips read-only. */
    FINANCE("Finance", EnumSet.of(OpsPermission.MONEY_VIEW, OpsPermission.MONEY_MANAGE, OpsPermission.TRIPS_VIEW,
            OpsPermission.DIRECTORY_VIEW, OpsPermission.CONSOLE_BASICS)),
    /** Trips and bookings, SOS, reports, ratings, account actions; money read-only. */
    SUPPORT("Support", EnumSet.of(OpsPermission.TRIPS_VIEW, OpsPermission.TRIPS_MANAGE, OpsPermission.SAFETY_VIEW,
            OpsPermission.SAFETY_MANAGE, OpsPermission.ACCOUNT_MANAGE, OpsPermission.DIRECTORY_VIEW, OpsPermission.MONEY_VIEW,
            OpsPermission.GROWTH_MANAGE, OpsPermission.CONSOLE_BASICS));

    /** The JWT claim carrying it. */
    public static final String CLAIM = "opsRole";

    private final String label;
    private final Set<OpsPermission> permissions;

    OpsRole(String label, Set<OpsPermission> permissions) {
        this.label = label;
        this.permissions = Collections.unmodifiableSet(permissions);
    }

    public String label() {
        return label;
    }

    public Set<OpsPermission> permissions() {
        return permissions;
    }

    public boolean can(OpsPermission permission) {
        return permissions.contains(permission);
    }

    /** The role a stored or claimed value names; null for null or blank. Throws on an unknown name. */
    public static OpsRole parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }

    /** What an OPS_ADMIN with this stored role acts as - no role yet means SUPER_ADMIN (today's behaviour). */
    public static OpsRole effective(OpsRole stored) {
        return stored == null ? SUPER_ADMIN : stored;
    }
}
