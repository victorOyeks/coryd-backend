package org.coryd.ops.access;

import org.coryd.common.enums.OpsPermission;
import org.springframework.http.HttpMethod;
import org.springframework.util.AntPathMatcher;

import java.util.List;
import java.util.Set;

/**
 * Which ops permission each console endpoint needs - one table, first match wins. Paths are relative to /ops. Anything
 * not listed needs SUPER_ADMIN ({@link #SUPER_ADMIN_ONLY}): a new endpoint is closed to the other roles until it's added
 * here. The role -> permission matrix itself is OpsRole (common) and docs/ops-console/admin-team.md.
 */
public final class OpsAccessPolicy {

    /** No rule matched: only a SUPER_ADMIN gets through. */
    public static final OpsPermission SUPER_ADMIN_ONLY = null;

    /** alsoAllowed: other permissions that open the same endpoint (any one is enough) - empty for most rules. */
    record Rule(HttpMethod method, String pattern, OpsPermission permission, Set<OpsPermission> alsoAllowed) {

        Rule(HttpMethod method, String pattern, OpsPermission permission) {
            this(method, pattern, permission, Set.of());
        }

        boolean matches(String method, String path) {
            return (this.method == null || this.method.name().equalsIgnoreCase(method)) && MATCHER.match(pattern, path);
        }
    }

    private static final AntPathMatcher MATCHER = new AntPathMatcher();
    private static final HttpMethod GET = HttpMethod.GET;
    private static final HttpMethod ANY = null;

    static final List<Rule> RULES = List.of(
            // every ops role
            new Rule(GET, "/me", OpsPermission.CONSOLE_BASICS),
            // an admin's own two-factor sign-in (phase 7)
            new Rule(ANY, "/me/mfa/**", OpsPermission.CONSOLE_BASICS),
            new Rule(ANY, "/stream/**", OpsPermission.CONSOLE_BASICS),
            new Rule(GET, "/metrics/**", OpsPermission.CONSOLE_BASICS),
            new Rule(GET, "/team/roles", OpsPermission.CONSOLE_BASICS),
            // super admin
            new Rule(ANY, "/team/**", OpsPermission.TEAM_MANAGE),
            new Rule(ANY, "/audit/**", OpsPermission.AUDIT_VIEW),
            // verification
            new Rule(GET, "/identity-verification/**", OpsPermission.VERIFICATION_VIEW),
            new Rule(ANY, "/identity-verification/**", OpsPermission.VERIFICATION_DECIDE),
            new Rule(GET, "/drivers/verification/**", OpsPermission.VERIFICATION_VIEW),
            new Rule(GET, "/drivers/*/verification/**", OpsPermission.VERIFICATION_VIEW),
            new Rule(ANY, "/drivers/*/verification/**", OpsPermission.VERIFICATION_DECIDE),
            new Rule(GET, "/document-expiry/**", OpsPermission.VERIFICATION_VIEW),
            new Rule(ANY, "/document-expiry/**", OpsPermission.VERIFICATION_DECIDE),
            // money
            new Rule(GET, "/money/**", OpsPermission.MONEY_VIEW),
            new Rule(ANY, "/money/**", OpsPermission.MONEY_MANAGE),
            // directories and account actions
            new Rule(GET, "/drivers/**", OpsPermission.DIRECTORY_VIEW),
            new Rule(GET, "/riders/**", OpsPermission.DIRECTORY_VIEW),
            new Rule(GET, "/users/*/account-history", OpsPermission.DIRECTORY_VIEW),
            new Rule(GET, "/account-deletions/**", OpsPermission.DIRECTORY_VIEW),
            new Rule(HttpMethod.PATCH, "/drivers/*/vehicles/*", OpsPermission.ACCOUNT_MANAGE),
            new Rule(HttpMethod.DELETE, "/users/*", OpsPermission.ACCOUNT_DELETE),
            // the audited reveal of a full NIN / licence number / date of birth (privacy.md): verification or account admins
            new Rule(HttpMethod.POST, "/users/*/reveal", OpsPermission.VERIFICATION_DECIDE, Set.of(OpsPermission.ACCOUNT_MANAGE)),
            new Rule(ANY, "/users/**", OpsPermission.ACCOUNT_MANAGE),
            // trips and bookings
            new Rule(GET, "/trips/**", OpsPermission.TRIPS_VIEW),
            new Rule(GET, "/cancellations/**", OpsPermission.TRIPS_VIEW),
            new Rule(ANY, "/trips/**", OpsPermission.TRIPS_MANAGE),
            new Rule(ANY, "/bookings/**", OpsPermission.TRIPS_MANAGE),
            // safety and support
            // Phase 6 platform: insights for every role; settings, broadcasts, emailed reports and health for super admins. The
            // analytics paths sit under /reports, so they must come before the safety /reports/** rules below.
            new Rule(GET, "/dashboard/**", OpsPermission.INSIGHTS_VIEW),
            new Rule(GET, "/corridors/**", OpsPermission.INSIGHTS_VIEW),
            new Rule(GET, "/reports/catalog", OpsPermission.INSIGHTS_VIEW),
            new Rule(GET, "/reports/analytics/**", OpsPermission.INSIGHTS_VIEW),
            new Rule(ANY, "/reports/emailed/**", OpsPermission.PLATFORM_MANAGE),
            new Rule(ANY, "/settings/**", OpsPermission.PLATFORM_MANAGE),
            new Rule(ANY, "/broadcasts/**", OpsPermission.PLATFORM_MANAGE),
            new Rule(ANY, "/system/**", OpsPermission.PLATFORM_MANAGE),

            new Rule(GET, "/sos/**", OpsPermission.SAFETY_VIEW),
            new Rule(GET, "/reports/**", OpsPermission.SAFETY_VIEW),
            new Rule(GET, "/ratings/**", OpsPermission.SAFETY_VIEW),
            new Rule(ANY, "/sos/**", OpsPermission.SAFETY_MANAGE),
            new Rule(ANY, "/reports/**", OpsPermission.SAFETY_MANAGE),
            new Rule(ANY, "/ratings/**", OpsPermission.SAFETY_MANAGE),
            // growth
            new Rule(ANY, "/beta-signups/**", OpsPermission.GROWTH_MANAGE));

    private OpsAccessPolicy() {
    }

    /** Every permission that lets this request through (any one is enough); empty means super admins only. */
    public static Set<OpsPermission> anyOf(String method, String path) {
        for (Rule rule : RULES) {
            if (rule.matches(method, path)) {
                Set<OpsPermission> all = new java.util.LinkedHashSet<>();
                all.add(rule.permission());
                all.addAll(rule.alsoAllowed());
                return all;
            }
        }
        return Set.of();
    }

    /** The (first) permission this request needs, or {@link #SUPER_ADMIN_ONLY}. */
    public static OpsPermission required(String method, String path) {
        for (Rule rule : RULES) {
            if (rule.matches(method, path)) {
                return rule.permission();
            }
        }
        return SUPER_ADMIN_ONLY;
    }
}
