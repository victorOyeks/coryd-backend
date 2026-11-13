package org.coryd.ops.access;

import org.coryd.common.enums.OpsPermission;
import org.coryd.common.enums.OpsRole;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpsAccessPolicyTest {

    @Test
    void readsAndActionsInTheSameGroupNeedDifferentPermissions() {
        assertThat(OpsAccessPolicy.required("GET", "/money/payouts")).isEqualTo(OpsPermission.MONEY_VIEW);
        assertThat(OpsAccessPolicy.required("POST", "/money/payouts/p-1/retry")).isEqualTo(OpsPermission.MONEY_MANAGE);
        assertThat(OpsAccessPolicy.required("GET", "/drivers")).isEqualTo(OpsPermission.DIRECTORY_VIEW);
        assertThat(OpsAccessPolicy.required("GET", "/drivers/d-1")).isEqualTo(OpsPermission.DIRECTORY_VIEW);
        assertThat(OpsAccessPolicy.required("GET", "/drivers/d-1/verification")).isEqualTo(OpsPermission.VERIFICATION_VIEW);
        assertThat(OpsAccessPolicy.required("POST", "/drivers/d-1/verification/licence/reset-attempts"))
                .isEqualTo(OpsPermission.VERIFICATION_DECIDE);
        assertThat(OpsAccessPolicy.required("PATCH", "/drivers/d-1/vehicles/v-1")).isEqualTo(OpsPermission.ACCOUNT_MANAGE);
        assertThat(OpsAccessPolicy.required("GET", "/users/u-1/account-history")).isEqualTo(OpsPermission.DIRECTORY_VIEW);
        assertThat(OpsAccessPolicy.required("PATCH", "/users/u-1/details")).isEqualTo(OpsPermission.ACCOUNT_MANAGE);
        assertThat(OpsAccessPolicy.required("DELETE", "/users/u-1")).isEqualTo(OpsPermission.ACCOUNT_DELETE);
        assertThat(OpsAccessPolicy.required("GET", "/team/roles")).isEqualTo(OpsPermission.CONSOLE_BASICS);
        assertThat(OpsAccessPolicy.required("POST", "/stream/token")).isEqualTo(OpsPermission.CONSOLE_BASICS);
        assertThat(OpsAccessPolicy.required("GET", "/not-yet-ruled")).as("an endpoint with no rule is super-admin only").isEqualTo(OpsAccessPolicy.SUPER_ADMIN_ONLY);
    }

    @Test
    void theRoleMatrixIsWhatThePlanSays() {
        assertThat(OpsRole.SUPER_ADMIN.permissions()).containsExactlyInAnyOrder(OpsPermission.values());
        assertThat(OpsRole.VERIFICATION.can(OpsPermission.VERIFICATION_DECIDE)).isTrue();
        assertThat(OpsRole.VERIFICATION.can(OpsPermission.ACCOUNT_MANAGE)).isFalse();
        assertThat(OpsRole.FINANCE.can(OpsPermission.MONEY_MANAGE)).isTrue();
        assertThat(OpsRole.FINANCE.can(OpsPermission.TRIPS_VIEW)).isTrue();
        assertThat(OpsRole.FINANCE.can(OpsPermission.TRIPS_MANAGE)).isFalse();
        assertThat(OpsRole.SUPPORT.can(OpsPermission.MONEY_VIEW)).isTrue();
        assertThat(OpsRole.SUPPORT.can(OpsPermission.MONEY_MANAGE)).isFalse();
        assertThat(OpsRole.SUPPORT.can(OpsPermission.ACCOUNT_DELETE)).isFalse();
        for (OpsRole role : OpsRole.values()) {
            assertThat(role.can(OpsPermission.CONSOLE_BASICS)).isTrue();
            assertThat(role == OpsRole.SUPER_ADMIN || !role.can(OpsPermission.TEAM_MANAGE)).isTrue();
            assertThat(role == OpsRole.SUPER_ADMIN || !role.can(OpsPermission.AUDIT_VIEW)).isTrue();
        }
        assertThat(OpsRole.effective(null)).isEqualTo(OpsRole.SUPER_ADMIN);
    }

    @Test
    void platformInsightsAreForEveryRoleAndPlatformControlForSuperAdminsOnly() {
        assertThat(OpsAccessPolicy.required("GET", "/dashboard")).isEqualTo(OpsPermission.INSIGHTS_VIEW);
        assertThat(OpsAccessPolicy.required("GET", "/corridors")).isEqualTo(OpsPermission.INSIGHTS_VIEW);
        assertThat(OpsAccessPolicy.required("GET", "/reports/catalog")).isEqualTo(OpsPermission.INSIGHTS_VIEW);
        assertThat(OpsAccessPolicy.required("GET", "/reports/analytics/revenue")).isEqualTo(OpsPermission.INSIGHTS_VIEW);
        assertThat(OpsAccessPolicy.required("GET", "/reports/analytics/revenue.csv")).isEqualTo(OpsPermission.INSIGHTS_VIEW);
        assertThat(OpsAccessPolicy.required("POST", "/reports/emailed/daily/send")).isEqualTo(OpsPermission.PLATFORM_MANAGE);
        assertThat(OpsAccessPolicy.required("GET", "/settings")).isEqualTo(OpsPermission.PLATFORM_MANAGE);
        assertThat(OpsAccessPolicy.required("PUT", "/settings/commission.percent")).isEqualTo(OpsPermission.PLATFORM_MANAGE);
        assertThat(OpsAccessPolicy.required("POST", "/broadcasts")).isEqualTo(OpsPermission.PLATFORM_MANAGE);
        assertThat(OpsAccessPolicy.required("GET", "/system/health")).isEqualTo(OpsPermission.PLATFORM_MANAGE);
        // Safety reports (issue reports) under the same /reports prefix keep their own permissions.
        assertThat(OpsAccessPolicy.required("GET", "/reports/r-1")).isEqualTo(OpsPermission.SAFETY_VIEW);
        assertThat(OpsAccessPolicy.required("POST", "/reports/r-1/resolve")).isEqualTo(OpsPermission.SAFETY_MANAGE);
        for (OpsRole role : OpsRole.values()) {
            assertThat(role.can(OpsPermission.INSIGHTS_VIEW)).as(role + " sees the dashboard").isTrue();
            assertThat(role.can(OpsPermission.PLATFORM_MANAGE)).as(role + " changes settings").isEqualTo(role == OpsRole.SUPER_ADMIN);
        }
    }
}
