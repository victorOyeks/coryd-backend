package org.coryd.notification.onboarding;

import org.coryd.common.enums.UserRole;
import org.coryd.common.security.CoRydUserDetails;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Running the jobs by hand is for super admins only - checked here too, not just in ops-service. */
class InternalOpsNotificationJobsControllerTest {

    @Test
    void onlySuperAdminsMayRunTheJobs() {
        assertThat(InternalOpsNotificationJobsController.superAdmin(
                new CoRydUserDetails("s-1", null, "Sam", "Boss", UserRole.OPS_ADMIN, "SUPER_ADMIN")).id()).isEqualTo("s-1");
        assertThat(InternalOpsNotificationJobsController.superAdmin(
                new CoRydUserDetails("s-2", null, "Old", "Token", UserRole.OPS_ADMIN, null)).id()).as("no role yet = super admin").isEqualTo("s-2");
        for (String role : new String[]{"VERIFICATION", "FINANCE", "SUPPORT", "JANITOR"}) {
            assertThatThrownBy(() -> InternalOpsNotificationJobsController.superAdmin(
                    new CoRydUserDetails("v-1", null, "V", "O", UserRole.OPS_ADMIN, role))).as(role).isInstanceOf(AccessDeniedException.class);
        }
        assertThatThrownBy(() -> InternalOpsNotificationJobsController.superAdmin(
                new CoRydUserDetails("r-1", null, "R", "R", UserRole.RIDER, null))).isInstanceOf(AccessDeniedException.class);
    }
}
