package org.coryd.common.security;

import org.coryd.common.enums.OpsPermission;
import org.coryd.common.security.filter.OpsPermissionFilter.Rule;

import java.util.List;

/**
 * The ops-facing endpoints each owning service exposes outside /internal/** (reachable through the gateway), and the ops
 * permission each needs from an OPS_ADMIN token that doesn't come with the internal API key (OpsPermissionFilter). Paths
 * are relative to the service's context path; first match wins. Mirrors ops-service's OpsAccessPolicy for the console
 * screens that call them. docs/ops-console/security.md lists them.
 *
 * <p>trip-service has none: every ops endpoint there is under /internal/ops/** and needs the internal key already.
 */
public final class OpsOwnedPaths {

    private OpsOwnedPaths() {
    }

    /** Driver verification queue and decisions, licence review, identity (NIN) checks. */
    public static final List<Rule> PROFILE_SERVICE = List.of(
            Rule.get("/drivers/verification/**", OpsPermission.VERIFICATION_VIEW),
            Rule.get("/drivers/*/verification-status", OpsPermission.VERIFICATION_VIEW),
            Rule.get("/drivers/*/verification/**", OpsPermission.VERIFICATION_VIEW),
            Rule.any("/drivers/*/verification", OpsPermission.VERIFICATION_DECIDE),
            Rule.any("/drivers/*/verification/**", OpsPermission.VERIFICATION_DECIDE),
            Rule.get("/identity-verification/**", OpsPermission.VERIFICATION_VIEW),
            Rule.any("/identity-verification/**", OpsPermission.VERIFICATION_DECIDE));

    /** Driver earnings, statements and debt; rider payment history; payout (bank) accounts. */
    public static final List<Rule> PAYMENT_SERVICE = List.of(
            Rule.get("/earnings/drivers/**", OpsPermission.MONEY_VIEW),
            Rule.any("/earnings/drivers/**", OpsPermission.MONEY_MANAGE),
            Rule.get("/earnings/transactions/batch", OpsPermission.MONEY_VIEW),
            Rule.get("/payments/riders/*/history", OpsPermission.MONEY_VIEW),
            // bank details - revealing them is MONEY_MANAGE in the console too
            Rule.any("/payout-recipients/drivers/**", OpsPermission.MONEY_MANAGE));
}
