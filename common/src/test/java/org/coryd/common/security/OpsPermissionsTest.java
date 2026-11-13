package org.coryd.common.security;

import org.coryd.common.enums.OpsPermission;
import org.coryd.common.enums.UserRole;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpsPermissionsTest {

    private static CoRydUserDetails ops(String opsRole) {
        return new CoRydUserDetails("a-1", null, "Ada", "Ops", UserRole.OPS_ADMIN, opsRole);
    }

    @Test
    void anOpsRoleGetsExactlyItsPermissions() {
        assertThat(OpsPermissions.refusal(ops("VERIFICATION"), OpsPermission.VERIFICATION_DECIDE)).isNull();
        assertThat(OpsPermissions.refusal(ops("FINANCE"), OpsPermission.VERIFICATION_DECIDE))
                .isEqualTo("Your ops role (FINANCE) can't decide identity checks or driver documents");
        assertThat(OpsPermissions.refusal(ops("SUPPORT"), OpsPermission.MONEY_MANAGE))
                .isEqualTo("Your ops role (SUPPORT) can't move money (refunds, approvals, payouts, debt) or reveal bank details");
    }

    @Test
    void noClaimIsASuperAdminAnUnknownOneNothingAndOtherRolesAreNotJudgedHere() {
        assertThat(OpsPermissions.refusal(ops(null), OpsPermission.TEAM_MANAGE)).isNull();
        assertThat(OpsPermissions.refusal(ops("JANITOR"), OpsPermission.CONSOLE_BASICS))
                .isEqualTo("Your ops role (JANITOR) isn't recognised - sign out and sign in again");
        assertThat(OpsPermissions.refusal(new CoRydUserDetails("r-1", "0801", "R", "R", UserRole.RIDER), OpsPermission.TEAM_MANAGE)).isNull();
    }

    @Test
    void superAdminOnlyMeansSuperAdminOnly() {
        assertThat(OpsPermissions.refusal(ops("SUPER_ADMIN"), OpsPermissions.SUPER_ADMIN_ONLY)).isNull();
        assertThat(OpsPermissions.refusal(ops("FINANCE"), OpsPermissions.SUPER_ADMIN_ONLY))
                .isEqualTo("Your ops role (FINANCE) can't use this part of the console - it's for super admins");
    }
}
