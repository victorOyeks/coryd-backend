package org.coryd.notification.security;

import org.coryd.common.config.SecurityBeans;
import org.coryd.common.enums.OpsRole;
import org.coryd.common.enums.UserRole;
import org.coryd.common.security.JwtUtil;
import org.coryd.common.security.filter.InternalApiKeyFilter;
import org.coryd.common.security.filter.JwtFilter;
import org.coryd.common.security.revocation.TokenRevocationRegistry;
import org.coryd.notification.config.SecurityConfig;
import org.coryd.notification.controller.DeviceTokenController;
import org.coryd.notification.controller.NotificationController;
import org.coryd.notification.controller.NotificationPreferencesController;
import org.coryd.notification.entity.DeviceToken;
import org.coryd.notification.entity.NotificationPreferences;
import org.coryd.notification.entity.UserNotification;
import org.coryd.notification.repository.DeviceTokenRepository;
import org.coryd.notification.repository.NotificationPreferencesRepository;
import org.coryd.notification.repository.UserNotificationRepository;
import org.coryd.notification.service.DeviceTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.AutoConfigureDataJpa;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A user's bell feed, notification switches and device tokens are theirs: through the real security chain and against
 * notification-service's own migrations, rider A can't read, mark, change or register anything for rider B (403, or 404 for
 * B's notification id), A can for A, another service (internal key) can for anyone, ops admins can't, and the FCM token
 * still moves to whoever registers it from their own account.
 */
@WebMvcTest(controllers = {NotificationController.class, NotificationPreferencesController.class, DeviceTokenController.class})
@AutoConfigureDataJpa
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import({SecurityConfig.class, SecurityBeans.class, JwtFilter.class, JwtUtil.class, TokenRevocationRegistry.class,
        NotificationOwnership.class, DeviceTokenService.class})
@TestPropertySource(properties = {
        "services.internal.api-key=" + NotificationOwnershipTest.KEY,
        "jwt.secret=test-secret-test-secret-test-secret-test-secret-0123456789"
})
class NotificationOwnershipTest {

    static final String KEY = "test-internal-key";
    static final String NOT_YOURS = "You can only manage your own notifications";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired MockMvc mockMvc;
    @Autowired JwtUtil jwtUtil;
    @Autowired UserNotificationRepository feed;
    @Autowired NotificationPreferencesRepository preferences;
    @Autowired DeviceTokenRepository devices;

    String bNotificationId;

    @BeforeEach
    void seed() {
        feed.deleteAll();
        preferences.deleteAll();
        devices.deleteAll();
        bNotificationId = feed.save(UserNotification.builder().userId("rider-b").title("Booked").body("Your seat is booked")
                .eventType("BOOKING_CONFIRMED").build()).getId();
        feed.save(UserNotification.builder().userId("rider-a").title("Hello").body("Welcome").eventType("BROADCAST").build());
        devices.save(DeviceToken.builder().userId("rider-b").fcmToken("token-b").build());
    }

    private String bearer(String userId, UserRole role, OpsRole opsRole) {
        return "Bearer " + jwtUtil.generateToken(userId, "0801", "A", "B", role, opsRole);
    }

    private MockHttpServletRequestBuilder asA(MockHttpServletRequestBuilder request) {
        return request.header("Authorization", bearer("rider-a", UserRole.RIDER, null));
    }

    private MockHttpServletRequestBuilder asService(MockHttpServletRequestBuilder request) {
        return request.header("Authorization", bearer("rider-a", UserRole.RIDER, null)).header(InternalApiKeyFilter.HEADER, KEY);
    }

    private static MockHttpServletRequestBuilder register(String userId, String token) {
        return post("/device-tokens/users/" + userId).contentType(MediaType.APPLICATION_JSON).content("{\"fcmToken\":\"" + token + "\"}");
    }

    @Test
    void riderACantTouchRiderBsFeed() throws Exception {
        mockMvc.perform(asA(get("/notifications/rider-b")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value(NOT_YOURS));
        mockMvc.perform(asA(get("/notifications/rider-b/unread-count"))).andExpect(status().isForbidden());
        mockMvc.perform(asA(post("/notifications/users/rider-b/read-all"))).andExpect(status().isForbidden());
        // B's notification looks exactly like a missing one
        mockMvc.perform(asA(post("/notifications/" + bNotificationId + "/read")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("Notification not found"));
        mockMvc.perform(asA(post("/notifications/no-such-id/read")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("Notification not found"));
        assertThat(feed.countByUserIdAndReadFalse("rider-b")).isEqualTo(1);
        // no token at all is still a 401
        mockMvc.perform(get("/notifications/rider-b")).andExpect(status().isUnauthorized());
    }

    @Test
    void riderACanUseTheirOwnFeed() throws Exception {
        mockMvc.perform(asA(get("/notifications/rider-a"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1)).andExpect(jsonPath("$.data[0].userId").value("rider-a"));
        mockMvc.perform(asA(get("/notifications/rider-a/unread-count"))).andExpect(status().isOk());
        String aId = feed.findByUserIdOrderByCreatedAtDesc("rider-a").get(0).getId();
        mockMvc.perform(asA(post("/notifications/" + aId + "/read"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.read").value(true));
        mockMvc.perform(asA(post("/notifications/users/rider-a/read-all"))).andExpect(status().isOk());
    }

    @Test
    void riderACantReadOrChangeRiderBsPreferences() throws Exception {
        mockMvc.perform(asA(get("/preferences/users/rider-b")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value(NOT_YOURS));
        mockMvc.perform(asA(put("/preferences/users/rider-b").param("promotionalOptIn", "false")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value(NOT_YOURS));
        assertThat(preferences.findByUserId("rider-b")).isEmpty();
    }

    @Test
    void theGetReturnsTheSwitchesWithoutStoringAnything() throws Exception {
        mockMvc.perform(asA(get("/preferences/users/rider-a"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value("rider-a"))
                .andExpect(jsonPath("$.data.promotionalOptIn").value(true))
                .andExpect(jsonPath("$.data.whatsappOptIn").value(true));
        assertThat(preferences.findByUserId("rider-a")).isEmpty();

        mockMvc.perform(asA(put("/preferences/users/rider-a").param("promotionalOptIn", "false"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.promotionalOptIn").value(false));
        mockMvc.perform(asA(get("/preferences/users/rider-a"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.promotionalOptIn").value(false))
                .andExpect(jsonPath("$.data.whatsappOptIn").value(true));
        assertThat(preferences.findByUserId("rider-a")).map(NotificationPreferences::isPromotionalOptIn).contains(false);
    }

    @Test
    void riderACantRegisterOrRemoveATokenOnRiderBsAccount() throws Exception {
        // the hijack: A's phone token registered under B so A gets B's pushes
        mockMvc.perform(asA(register("rider-b", "token-a")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value(NOT_YOURS));
        assertThat(devices.findByFcmToken("token-a")).isEmpty();
        mockMvc.perform(asA(delete("/device-tokens/users/rider-b").param("fcmToken", "token-b")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value(NOT_YOURS));
        assertThat(devices.findByFcmToken("token-b")).map(DeviceToken::getUserId).contains("rider-b");
    }

    @Test
    void aTokenStillMovesToWhoeverSignsInOnThatPhone() throws Exception {
        // B signs out without removing the token, A signs in on the same phone and registers it for themself
        mockMvc.perform(asA(register("rider-a", "token-b"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value("rider-a"));
        assertThat(devices.findByFcmToken("token-b")).map(DeviceToken::getUserId).contains("rider-a");
        assertThat(devices.findByUserId("rider-b")).isEmpty();
        assertThat(devices.count()).isEqualTo(1);
        // re-registering the same token is idempotent
        mockMvc.perform(asA(register("rider-a", "token-b"))).andExpect(status().isOk());
        assertThat(devices.count()).isEqualTo(1);
        // and A can remove their own token on sign-out
        mockMvc.perform(asA(delete("/device-tokens/users/rider-a").param("fcmToken", "token-b"))).andExpect(status().isOk());
        assertThat(devices.findByFcmToken("token-b")).isEmpty();
    }

    @Test
    void anotherServiceCanActForAnyUser() throws Exception {
        mockMvc.perform(asService(get("/notifications/rider-b"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].userId").value("rider-b"));
        mockMvc.perform(asService(get("/notifications/rider-b/unread-count"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").exists());
        mockMvc.perform(asService(post("/notifications/" + bNotificationId + "/read"))).andExpect(status().isOk());
        mockMvc.perform(asService(post("/notifications/users/rider-b/read-all"))).andExpect(status().isOk());
        mockMvc.perform(asService(get("/preferences/users/rider-b"))).andExpect(status().isOk());
        mockMvc.perform(asService(put("/preferences/users/rider-b").param("whatsappOptIn", "false"))).andExpect(status().isOk());
        mockMvc.perform(asService(register("rider-b", "token-b2"))).andExpect(status().isOk());
        mockMvc.perform(asService(delete("/device-tokens/users/rider-b").param("fcmToken", "token-b2"))).andExpect(status().isOk());
        // a wrong key is no key
        mockMvc.perform(asA(get("/notifications/rider-b")).header(InternalApiKeyFilter.HEADER, "wrong"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value(NOT_YOURS));
    }

    @Test
    void opsAdminsAndDriversAreRefusedToo() throws Exception {
        mockMvc.perform(get("/notifications/rider-b").header("Authorization", bearer("ops-1", UserRole.OPS_ADMIN, OpsRole.SUPER_ADMIN)))
                .andExpect(status().isForbidden());
        mockMvc.perform(register("rider-b", "token-d").header("Authorization", bearer("driver-1", UserRole.DRIVER, null)))
                .andExpect(status().isForbidden());
    }
}
