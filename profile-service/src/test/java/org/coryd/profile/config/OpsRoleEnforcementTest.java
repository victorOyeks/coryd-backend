package org.coryd.profile.config;

import org.coryd.common.config.SecurityBeans;
import org.coryd.common.enums.OpsRole;
import org.coryd.common.enums.UserRole;
import org.coryd.common.security.JwtUtil;
import org.coryd.common.security.filter.InternalApiKeyFilter;
import org.coryd.common.security.filter.JwtFilter;
import org.coryd.common.security.revocation.TokenRevocationRegistry;
import org.coryd.profile.controller.DriverProfileController;
import org.coryd.profile.controller.IdentityVerificationController;
import org.coryd.profile.service.DriverProfileService;
import org.coryd.profile.service.FavoriteDriverService;
import org.coryd.profile.service.IdentityReviewService;
import org.coryd.profile.service.IdentityVerificationService;
import org.coryd.profile.service.LicenceAttemptsService;
import org.coryd.profile.service.LicenceReviewService;
import org.coryd.profile.service.NinLookupService;
import org.coryd.profile.service.SmileIdCheckService;
import org.coryd.profile.service.VerificationAggregationService;
import org.coryd.profile.service.VerificationReviewService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 7: profile-service's ops-facing verification endpoints through the real security chain. A FINANCE admin calling
 * them directly through the gateway used to get in (any OPS_ADMIN token did); now that's 403, while ops-service (internal
 * key + the admin's token) and the right ops role still get through, and drivers reading their own status are untouched.
 */
@WebMvcTest(controllers = {DriverProfileController.class, IdentityVerificationController.class})
@Import({SecurityConfig.class, SecurityBeans.class, JwtFilter.class, JwtUtil.class, TokenRevocationRegistry.class})
@TestPropertySource(properties = {
        "services.internal.api-key=" + OpsRoleEnforcementTest.KEY,
        "jwt.secret=test-secret-test-secret-test-secret-test-secret-0123456789"
})
class OpsRoleEnforcementTest {

    static final String KEY = "test-internal-key";

    @Autowired MockMvc mockMvc;
    @Autowired JwtUtil jwtUtil;
    /** The application class enables JPA auditing; a web slice has no JPA. */
    @MockBean org.springframework.data.jpa.mapping.JpaMetamodelMappingContext jpaMappingContext;

    @MockBean DriverProfileService driverProfileService;
    @MockBean VerificationAggregationService verificationAggregationService;
    @MockBean VerificationReviewService verificationReviewService;
    @MockBean LicenceReviewService licenceReviewService;
    @MockBean LicenceAttemptsService licenceAttemptsService;
    @MockBean FavoriteDriverService favoriteDriverService;
    @MockBean SmileIdCheckService smileIdCheckService;
    @MockBean NinLookupService ninLookupService;
    @MockBean IdentityVerificationService identityVerificationService;
    @MockBean IdentityReviewService identityReviewService;

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, UserRole role, OpsRole opsRole) {
        return request.header("Authorization", "Bearer " + jwtUtil.generateToken("u-1", null, "Ada", "Ops", role, opsRole));
    }

    @Test
    void aFinanceAdminGoingStraightToProfileServiceIsRefused() throws Exception {
        mockMvc.perform(as(post("/drivers/d-1/verification/approve").contentType(MediaType.APPLICATION_JSON).content("{}"),
                        UserRole.OPS_ADMIN, OpsRole.FINANCE))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Your ops role (FINANCE) can't decide identity checks or driver documents"));
        mockMvc.perform(as(get("/drivers/verification/queue"), UserRole.OPS_ADMIN, OpsRole.FINANCE))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Your ops role (FINANCE) can't view identity checks or driver verification"));
        mockMvc.perform(as(get("/identity-verification/people"), UserRole.OPS_ADMIN, OpsRole.SUPPORT))
                .andExpect(status().isForbidden());
    }

    @Test
    void theVerificationRoleAndOpsServiceStillGetThrough() throws Exception {
        mockMvc.perform(as(get("/drivers/verification/queue"), UserRole.OPS_ADMIN, OpsRole.VERIFICATION)).andExpect(status().isOk());
        mockMvc.perform(as(get("/identity-verification/people"), UserRole.OPS_ADMIN, OpsRole.SUPER_ADMIN)).andExpect(status().isOk());
        // ops-service: the admin's own token plus the internal key - it already enforced the ops role
        mockMvc.perform(as(get("/drivers/verification/queue"), UserRole.OPS_ADMIN, OpsRole.FINANCE)
                .header(InternalApiKeyFilter.HEADER, KEY)).andExpect(status().isOk());
        // a service's system token (no ops role) counts as super admin
        mockMvc.perform(get("/drivers/verification/queue").header("Authorization",
                "Bearer " + jwtUtil.generateToken("system", null, "CoRyd", "System", UserRole.OPS_ADMIN))).andExpect(status().isOk());
    }

    @Test
    void driversAreUntouched() throws Exception {
        mockMvc.perform(as(get("/drivers/u-1/verification-status"), UserRole.DRIVER, null)).andExpect(status().isOk());
    }
}
