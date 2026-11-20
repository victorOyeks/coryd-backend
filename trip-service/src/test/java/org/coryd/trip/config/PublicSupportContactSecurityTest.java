package org.coryd.trip.config;

import org.coryd.common.config.SecurityBeans;
import org.coryd.common.security.JwtUtil;
import org.coryd.common.security.filter.JwtFilter;
import org.coryd.common.security.revocation.TokenRevocationRegistry;
import org.coryd.common.settings.PlatformSetting;
import org.coryd.trip.settings.PlatformSettingDefaults;
import org.coryd.trip.settings.SupportContactController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contact support is readable with no account at all - a suspended rider or driver can't sign in - and a stale or bogus token
 * the app still holds never turns it into a 401. Only GET is open.
 */
@WebMvcTest(controllers = SupportContactController.class)
@Import({SecurityConfig.class, SecurityBeans.class, JwtFilter.class, JwtUtil.class, TokenRevocationRegistry.class})
@TestPropertySource(properties = {
        "services.internal.api-key=test-internal-key",
        "jwt.secret=test-secret-test-secret-test-secret-test-secret-0123456789"
})
class PublicSupportContactSecurityTest {

    @Autowired MockMvc mockMvc;
    @MockBean org.springframework.data.jpa.mapping.JpaMetamodelMappingContext jpaMappingContext;
    @MockBean PlatformSettingDefaults defaults;

    @BeforeEach
    void configured() {
        when(defaults.of(PlatformSetting.SUPPORT_WHATSAPP)).thenReturn("+2348031234567");
        when(defaults.of(PlatformSetting.SUPPORT_EMAIL)).thenReturn("none");
    }

    @Test
    void anyoneCanReadItWithoutSigningIn() throws Exception {
        mockMvc.perform(get("/public/support-contact"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("max-age=300")))
                .andExpect(jsonPath("$.data.whatsapp").value("+2348031234567"))
                .andExpect(jsonPath("$.data.whatsappLink").value("https://wa.me/2348031234567"))
                .andExpect(jsonPath("$.data.email").doesNotExist());
    }

    @Test
    void aStaleTokenDoesNotBlockIt() throws Exception {
        mockMvc.perform(get("/public/support-contact").header("Authorization", "Bearer expired.or.revoked"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.whatsapp").value("+2348031234567"));
    }

    @Test
    void onlyReadsAreOpen() throws Exception {
        mockMvc.perform(post("/public/support-contact")).andExpect(status().isUnauthorized());
    }
}
