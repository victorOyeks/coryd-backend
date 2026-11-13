package org.coryd.ops.privacy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.coryd.common.enums.OpsRole;
import org.coryd.common.enums.UserRole;
import org.coryd.common.security.JwtUtil;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /ops/users/{id}/reveal and the bank account reveal (docs/ops-console/privacy.md) against a stand-in profile- and
 * payment-service: a reason and a known field are required before anything is called, the right roles get through, the
 * value comes back with its 60-second expiry, and reveals are rate limited per admin (ID and bank reveals share the budget).
 */
@SpringBootTest(properties = {
        "spring.kafka.bootstrap-servers=localhost:1",
        "spring.kafka.admin.auto-create=false",
        "services.internal.api-key=test-internal-key",
        "jwt.secret=test-secret-test-secret-test-secret-test-secret-0123456789",
        "ops.privacy.reveals-per-hour=3"
})
@AutoConfigureMockMvc
class OpsPrivacyApiTest {

    private static final HttpServer UPSTREAM = startUpstream();
    private static final Map<String, String> replies = new ConcurrentHashMap<>();
    private static final List<String> requests = new CopyOnWriteArrayList<>();

    @DynamicPropertySource
    static void upstreamUrls(DynamicPropertyRegistry registry) {
        String base = "http://localhost:" + UPSTREAM.getAddress().getPort();
        registry.add("services.profile.url", () -> base + "/profile");
        registry.add("services.trip.url", () -> base + "/trip");
        registry.add("services.identity.url", () -> base + "/identity");
        registry.add("services.payment.url", () -> base + "/payment");
        registry.add("services.notification.url", () -> base + "/notification");
    }

    @Autowired MockMvc mockMvc;
    @Autowired JwtUtil jwtUtil;

    @BeforeEach
    void reset() {
        replies.clear();
        requests.clear();
        replies.put("/profile/internal/ops/users/u-1/reveal", "{\"success\":true,\"data\":{\"field\":\"NIN\",\"value\":\"12345675040\","
                + "\"source\":\"NIMC_RECORD\",\"revealedAt\":\"2026-11-13T10:00:00Z\",\"expiresInSeconds\":60}}");
        replies.put("/payment/internal/ops/money/drivers/d-1/payout-account/reveal", "{\"success\":true,\"data\":{\"accountNumber\":\"0123456789\","
                + "\"expiresInSeconds\":60}}");
    }

    @AfterAll
    static void stop() {
        UPSTREAM.stop(0);
    }

    private String bearer(String id, OpsRole role) {
        return "Bearer " + jwtUtil.generateToken(id, null, "Ada", "Admin", UserRole.OPS_ADMIN, role);
    }

    private org.springframework.test.web.servlet.ResultActions reveal(String token, String body) throws Exception {
        return mockMvc.perform(post("/users/u-1/reveal").header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void aReasonAndAKnownFieldAreRequiredBeforeAnythingIsCalled() throws Exception {
        String admin = bearer("v-1", OpsRole.VERIFICATION);
        reveal(admin, "{\"field\":\"NIN\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Say why you need to see it - it goes in the audit log"));
        reveal(admin, "{\"field\":\"NIN\",\"reason\":\"   \"}").andExpect(status().isBadRequest());
        reveal(admin, "{\"field\":\"BVN\",\"reason\":\"x\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("field must be one of [NIN, LICENCE_NUMBER, DATE_OF_BIRTH]"));
        mockMvc.perform(post("/money/drivers/d-1/payout-account/reveal").header("Authorization", bearer("f-1", OpsRole.FINANCE))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Say why you need to see it - it goes in the audit log"));
        assertThat(requests).isEmpty();
    }

    @Test
    void theValueComesBackWithItsExpiryAndTheReasonGoesToProfileService() throws Exception {
        reveal(bearer("s-1", OpsRole.SUPPORT), "{\"field\":\"nin\",\"reason\":\"Rider disputes the match\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.field").value("NIN"))
                .andExpect(jsonPath("$.data.value").value("12345675040"))
                .andExpect(jsonPath("$.data.expiresInSeconds").value(60));
        assertThat(requests).singleElement().satisfies(r -> assertThat(r).contains("\"field\":\"NIN\"").contains("Rider disputes the match"));
    }

    @Test
    void financeCantRevealIdNumbers() throws Exception {
        reveal(bearer("f-1", OpsRole.FINANCE), "{\"field\":\"NIN\",\"reason\":\"x\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Your ops role (FINANCE) can't reveal ID numbers or dates of birth - ask a verification or support admin"));
        assertThat(requests).isEmpty();
    }

    @Test
    void revealsAreRateLimitedPerAdminAcrossIdAndBankReveals() throws Exception {
        String boss = bearer("boss-rate", OpsRole.SUPER_ADMIN);
        reveal(boss, "{\"field\":\"NIN\",\"reason\":\"one\"}").andExpect(status().isOk());
        mockMvc.perform(post("/money/drivers/d-1/payout-account/reveal").header("Authorization", boss)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"two\"}")).andExpect(status().isOk());
        replies.put("/profile/internal/ops/users/u-1/reveal", "{\"success\":false,\"message\":\"This person hasn't given a NIN\"}");
        replyStatus.put("/profile/internal/ops/users/u-1/reveal", 404);
        reveal(boss, "{\"field\":\"NIN\",\"reason\":\"not on file\"}").andExpect(status().isNotFound());
        replyStatus.clear();
        reset();
        reveal(boss, "{\"field\":\"NIN\",\"reason\":\"three - the 404 didn't count\"}").andExpect(status().isOk());
        reveal(boss, "{\"field\":\"NIN\",\"reason\":\"four\"}").andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.startsWith("You've revealed 3 details in the last hour")));
        assertThat(requests).as("the refused one never left ops-service").hasSize(1);
        reveal(bearer("someone-else", OpsRole.SUPER_ADMIN), "{\"field\":\"NIN\",\"reason\":\"own budget\"}").andExpect(status().isOk());
    }

    @Test
    void theWindowRolls() {
        Instant[] now = {Instant.parse("2026-11-13T10:00:00Z")};
        Clock clock = new Clock() {
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return now[0]; }
        };
        RevealRateLimiter limiter = new RevealRateLimiter(2, clock);
        limiter.acquire("a");
        now[0] = now[0].plusSeconds(1800);
        limiter.acquire("a");
        assertThatThrownBy(() -> limiter.acquire("a")).hasMessageContaining("try again in 31 minutes");
        now[0] = now[0].plusSeconds(1801);
        limiter.acquire("a");
    }

    private static final Map<String, Integer> replyStatus = new ConcurrentHashMap<>();

    private static HttpServer startUpstream() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", OpsPrivacyApiTest::handle);
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (path.contains("/reveal")) {
            requests.add(body);
        }
        byte[] response = replies.getOrDefault(path, "{\"success\":true,\"data\":null}").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(replyStatus.getOrDefault(path, 200), response.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(response);
        }
    }
}
