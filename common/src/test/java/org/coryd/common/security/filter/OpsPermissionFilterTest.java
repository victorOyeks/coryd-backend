package org.coryd.common.security.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.coryd.common.enums.UserRole;
import org.coryd.common.security.CoRydUserDetails;
import org.coryd.common.security.OpsOwnedPaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;

/** The owning services' ops-role gate: a role bypass through the gateway is refused, ops-service (with the key) isn't. */
class OpsPermissionFilterTest {

    private static final String KEY = "internal-key";
    private final OpsPermissionFilter profile = new OpsPermissionFilter(OpsOwnedPaths.PROFILE_SERVICE, KEY,
            new ObjectMapper().registerModule(new JavaTimeModule()));
    private final OpsPermissionFilter payment = new OpsPermissionFilter(OpsOwnedPaths.PAYMENT_SERVICE, KEY,
            new ObjectMapper().registerModule(new JavaTimeModule()));

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void signIn(UserRole role, String opsRole) {
        CoRydUserDetails user = new CoRydUserDetails("u-1", null, "A", "B", role, opsRole);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
    }

    private static MockHttpServletResponse run(OpsPermissionFilter filter, String method, String path, String key) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/profile" + path);
        request.setContextPath("/profile");
        if (key != null) {
            request.addHeader(InternalApiKeyFilter.HEADER, key);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Test
    void aFinanceAdminCantDecideVerificationDirectlyButAVerificationAdminCan() throws Exception {
        signIn(UserRole.OPS_ADMIN, "FINANCE");
        MockHttpServletResponse refused = run(profile, "POST", "/drivers/d-1/verification/approve", null);
        assertThat(refused.getStatus()).isEqualTo(403);
        assertThat(refused.getContentAsString()).contains("Your ops role (FINANCE) can't decide identity checks or driver documents");
        assertThat(run(profile, "GET", "/identity-verification/people", null).getStatus()).isEqualTo(403);
        assertThat(run(profile, "PATCH", "/drivers/d-1/verification", null).getStatus()).isEqualTo(403);

        signIn(UserRole.OPS_ADMIN, "VERIFICATION");
        assertThat(run(profile, "POST", "/drivers/d-1/verification/approve", null).getStatus()).isEqualTo(200);
        assertThat(run(profile, "GET", "/drivers/verification/queue", null).getStatus()).isEqualTo(200);
    }

    @Test
    void opsServiceWithTheInternalKeyPassesForAnyRoleItAlreadyChecked() throws Exception {
        signIn(UserRole.OPS_ADMIN, "FINANCE");
        assertThat(run(profile, "POST", "/drivers/d-1/verification/approve", KEY).getStatus()).isEqualTo(200);
        assertThat(run(profile, "POST", "/drivers/d-1/verification/approve", "wrong-key").getStatus()).isEqualTo(403);
    }

    @Test
    void ridersDriversAndUnlistedPathsAreUntouched() throws Exception {
        signIn(UserRole.DRIVER, null);
        assertThat(run(profile, "GET", "/drivers/u-1/verification-status", null).getStatus()).isEqualTo(200);
        signIn(UserRole.OPS_ADMIN, "SUPPORT");
        assertThat(run(profile, "GET", "/drivers/d-1", null).getStatus()).isEqualTo(200);
    }

    @Test
    void moneyEndpointsNeedTheMoneyPermissions() throws Exception {
        signIn(UserRole.OPS_ADMIN, "VERIFICATION");
        assertThat(run(payment, "GET", "/earnings/drivers/d-1/summary", null).getStatus()).isEqualTo(403);
        signIn(UserRole.OPS_ADMIN, "SUPPORT");
        assertThat(run(payment, "GET", "/earnings/drivers/d-1/summary", null).getStatus()).isEqualTo(200);
        assertThat(run(payment, "POST", "/earnings/drivers/d-1/settlement-debt", null).getStatus()).isEqualTo(403);
        assertThat(run(payment, "GET", "/payout-recipients/drivers/d-1", null).getStatus()).as("bank details").isEqualTo(403);
        signIn(UserRole.OPS_ADMIN, "FINANCE");
        assertThat(run(payment, "POST", "/earnings/drivers/d-1/settlement-debt", null).getStatus()).isEqualTo(200);
        assertThat(run(payment, "GET", "/payout-recipients/drivers/d-1", null).getStatus()).isEqualTo(200);
    }
}
