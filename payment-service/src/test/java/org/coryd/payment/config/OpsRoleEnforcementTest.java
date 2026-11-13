package org.coryd.payment.config;

import org.coryd.common.config.SecurityBeans;
import org.coryd.common.enums.OpsRole;
import org.coryd.common.enums.UserRole;
import org.coryd.common.security.JwtUtil;
import org.coryd.common.security.filter.InternalApiKeyFilter;
import org.coryd.common.security.filter.JwtFilter;
import org.coryd.common.security.revocation.TokenRevocationRegistry;
import org.coryd.payment.controller.EarningsController;
import org.coryd.payment.controller.PaymentController;
import org.coryd.payment.service.EarningsService;
import org.coryd.payment.service.PaymentHistoryService;
import org.coryd.payment.service.PaymentService;
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
 * Phase 7: payment-service's ops-facing earnings and history endpoints need the ops role's money permissions when called
 * directly (not through ops-service with the internal key). Drivers reading their own earnings are untouched.
 */
@WebMvcTest(controllers = {EarningsController.class, PaymentController.class})
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

    @MockBean EarningsService earningsService;
    @MockBean PaymentService paymentService;
    @MockBean PaymentHistoryService paymentHistoryService;
    @MockBean org.coryd.payment.gateway.PaymentGatewayClient gatewayClient;
    @MockBean org.coryd.payment.security.BookingPaymentAccess bookingPaymentAccess;

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, UserRole role, OpsRole opsRole) {
        return request.header("Authorization", "Bearer " + jwtUtil.generateToken("u-1", null, "Ada", "Ops", role, opsRole));
    }

    @Test
    void aVerificationAdminCantReadMoneyAndSupportCantMoveIt() throws Exception {
        mockMvc.perform(as(get("/earnings/drivers/d-1/summary"), UserRole.OPS_ADMIN, OpsRole.VERIFICATION))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Your ops role (VERIFICATION) can't view money"));
        mockMvc.perform(as(get("/payments/riders/r-1/history"), UserRole.OPS_ADMIN, OpsRole.VERIFICATION))
                .andExpect(status().isForbidden());
        mockMvc.perform(as(get("/earnings/drivers/d-1/summary"), UserRole.OPS_ADMIN, OpsRole.SUPPORT)).andExpect(status().isOk());
        mockMvc.perform(as(post("/earnings/drivers/d-1/settlement-debt").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":100,\"reason\":\"Manual refund\"}"), UserRole.OPS_ADMIN, OpsRole.SUPPORT))
                .andExpect(status().isForbidden());
    }

    @Test
    void financeAndOpsServiceGetThroughAndDriversAreUntouched() throws Exception {
        mockMvc.perform(as(post("/earnings/drivers/d-1/settlement-debt").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":100,\"reason\":\"Manual refund\"}"), UserRole.OPS_ADMIN, OpsRole.FINANCE))
                .andExpect(status().isOk());
        mockMvc.perform(as(get("/earnings/drivers/d-1/summary"), UserRole.OPS_ADMIN, OpsRole.VERIFICATION)
                .header(InternalApiKeyFilter.HEADER, KEY)).andExpect(status().isOk());
        mockMvc.perform(as(get("/earnings/drivers/u-1/summary"), UserRole.DRIVER, null)).andExpect(status().isOk());
    }
}
