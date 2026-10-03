package org.coryd.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Boots the real service - no database anywhere - against a stand-in profile-service listening on a
 * real port, so the calls go out over actual HTTP (PATCH included) with the admin's token on them.
 */
@SpringBootTest(properties = {
        "spring.kafka.bootstrap-servers=localhost:1",
        "spring.kafka.admin.auto-create=false",
        "services.internal.api-key=test-internal-key",
        "jwt.secret=test-secret-test-secret-test-secret-test-secret-0123456789"
})
@AutoConfigureMockMvc
class OpsServiceIntegrationTest {

    private static final HttpServer PROFILE = startFakeProfileService();
    private static final AtomicReference<String> lastMethod = new AtomicReference<>();
    private static final AtomicReference<String> lastPath = new AtomicReference<>();
    private static final AtomicReference<String> lastAuth = new AtomicReference<>();
    private static final AtomicReference<String> lastBody = new AtomicReference<>();
    private static final AtomicReference<Integer> nextStatus = new AtomicReference<>(200);
    private static final AtomicReference<String> nextBody = new AtomicReference<>("{}");

    @DynamicPropertySource
    static void profileUrl(DynamicPropertyRegistry registry) {
        registry.add("services.profile.url", () -> "http://localhost:" + PROFILE.getAddress().getPort() + "/profile");
    }

    @Autowired MockMvc mockMvc;
    @Autowired JwtUtil jwtUtil;
    @Autowired ObjectMapper objectMapper;

    @BeforeEach
    void reset() {
        lastMethod.set(null);
        lastPath.set(null);
        lastAuth.set(null);
        lastBody.set(null);
        nextStatus.set(200);
        nextBody.set("{\"success\":true,\"data\":{\"overallStatus\":\"IN_REVIEW\"}}");
    }

    @AfterAll
    static void stop() {
        PROFILE.stop(0);
    }

    @Test
    void startsAndReportsHealthyWithNoDatabase() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void onlyOpsAdminsGetIn() throws Exception {
        mockMvc.perform(get("/drivers/verification/queue")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/drivers/verification/queue").header("Authorization", bearer(UserRole.RIDER)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/drivers/verification/queue").header("Authorization", bearer(UserRole.DRIVER)))
                .andExpect(status().isForbidden());
        assertThat(lastPath.get()).as("a refused caller never reaches profile-service").isNull();
    }

    @Test
    void queueIsRelayedWithTheAdminsOwnToken() throws Exception {
        nextBody.set("{\"success\":true,\"data\":[{\"driverId\":\"d-1\",\"pendingItems\":[\"VEHICLE\"]}]}");
        String token = bearer(UserRole.OPS_ADMIN);

        mockMvc.perform(get("/drivers/verification/queue").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].driverId").value("d-1"))
                .andExpect(jsonPath("$.data[0].pendingItems[0]").value("VEHICLE"));

        assertThat(lastMethod.get()).isEqualTo("GET");
        assertThat(lastPath.get()).isEqualTo("/profile/drivers/verification/pending");
        assertThat(lastAuth.get()).isEqualTo(token);
    }

    @Test
    void decisionGoesOutAsPatchSignedByTheAdmin() throws Exception {
        mockMvc.perform(post("/drivers/d-1/verification/decisions").header("Authorization", bearer(UserRole.OPS_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decisions\":[{\"item\":\"VEHICLE\",\"status\":\"APPROVED\"}],\"reviewedBy\":\"someone-else\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.overallStatus").value("IN_REVIEW"));

        assertThat(lastMethod.get()).isEqualTo("PATCH");
        assertThat(lastPath.get()).isEqualTo("/profile/drivers/d-1/verification");
        JsonNode sent = objectMapper.readTree(lastBody.get());
        assertThat(sent.get("decisions").get(0).get("item").asText()).isEqualTo("VEHICLE");
        assertThat(sent.get("reviewedBy").asText()).as("can't be written in someone else's name").isEqualTo("Ada Ops");
    }

    @Test
    void profileServiceRefusalComesBackWithItsOwnStatusAndMessage() throws Exception {
        nextStatus.set(400);
        nextBody.set("{\"success\":false,\"message\":\"A rejection reason is required\"}");

        mockMvc.perform(post("/drivers/d-1/verification/decisions").header("Authorization", bearer(UserRole.OPS_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decisions\":[{\"item\":\"VEHICLE\",\"status\":\"REJECTED\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("A rejection reason is required"));
    }

    @Test
    void unknownDriverIsA404NotA500() throws Exception {
        nextStatus.set(404);
        nextBody.set("{\"success\":false,\"message\":\"Driver profile not found\"}");

        mockMvc.perform(get("/drivers/nobody/verification").header("Authorization", bearer(UserRole.OPS_ADMIN)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Driver profile not found"));
    }

    private String bearer(UserRole role) {
        return "Bearer " + jwtUtil.generateToken("admin-1", "08000000000", "Ada", "Ops", role);
    }

    private static HttpServer startFakeProfileService() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/profile", OpsServiceIntegrationTest::handle);
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void handle(HttpExchange exchange) throws IOException {
        lastMethod.set(exchange.getRequestMethod());
        lastPath.set(exchange.getRequestURI().getPath());
        lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
        lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        byte[] response = nextBody.get().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(nextStatus.get(), response.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(response);
        }
    }
}
