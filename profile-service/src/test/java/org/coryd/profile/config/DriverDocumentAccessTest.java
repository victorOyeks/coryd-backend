package org.coryd.profile.config;

import org.coryd.common.config.SecurityBeans;
import org.coryd.common.enums.OpsRole;
import org.coryd.common.enums.UserRole;
import org.coryd.common.security.JwtUtil;
import org.coryd.common.security.filter.InternalApiKeyFilter;
import org.coryd.common.security.filter.JwtFilter;
import org.coryd.common.security.revocation.TokenRevocationRegistry;
import org.coryd.profile.controller.DriverDocumentController;
import org.coryd.profile.security.DriverDocumentAccess;
import org.coryd.profile.service.DriverDocumentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A driver's documents are theirs: not readable by another driver or any rider; the driver, ops (VERIFICATION_VIEW) and services can. */
@WebMvcTest(controllers = DriverDocumentController.class)
@Import({SecurityConfig.class, SecurityBeans.class, JwtFilter.class, JwtUtil.class, TokenRevocationRegistry.class, DriverDocumentAccess.class})
@TestPropertySource(properties = {
        "services.internal.api-key=" + DriverDocumentAccessTest.KEY,
        "jwt.secret=test-secret-test-secret-test-secret-test-secret-0123456789"
})
class DriverDocumentAccessTest {

    static final String KEY = "test-internal-key";

    @Autowired MockMvc mockMvc;
    @Autowired JwtUtil jwtUtil;
    @MockBean org.springframework.data.jpa.mapping.JpaMetamodelMappingContext jpaMappingContext;
    @MockBean DriverDocumentService driverDocumentService;

    @BeforeEach
    void documents() {
        when(driverDocumentService.findByDriver("driver-a")).thenReturn(List.of());
    }

    private MockHttpServletRequestBuilder as(String userId, UserRole role, OpsRole opsRole) {
        return get("/drivers/driver-a/documents")
                .header("Authorization", "Bearer " + jwtUtil.generateToken(userId, "0801", "A", "B", role, opsRole));
    }

    @Test
    void anotherDriverOrARiderCantReadThem() throws Exception {
        mockMvc.perform(as("driver-b", UserRole.DRIVER, null))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("You can only view your own documents"));
        mockMvc.perform(as("rider-1", UserRole.RIDER, null)).andExpect(status().isForbidden());
        mockMvc.perform(get("/drivers/driver-a/documents")).andExpect(status().isUnauthorized());
    }

    @Test
    void theDriverOpsWithVerificationViewAndServicesCan() throws Exception {
        mockMvc.perform(as("driver-a", UserRole.DRIVER, null)).andExpect(status().isOk());
        mockMvc.perform(as("ops-1", UserRole.OPS_ADMIN, OpsRole.VERIFICATION)).andExpect(status().isOk());
        mockMvc.perform(as("ops-2", UserRole.OPS_ADMIN, OpsRole.FINANCE))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Your ops role (FINANCE) can't view identity checks or driver verification"));
        mockMvc.perform(as("rider-1", UserRole.RIDER, null).header(InternalApiKeyFilter.HEADER, KEY)).andExpect(status().isOk());
        mockMvc.perform(as("rider-1", UserRole.RIDER, null).header(InternalApiKeyFilter.HEADER, "wrong")).andExpect(status().isForbidden());
    }
}
