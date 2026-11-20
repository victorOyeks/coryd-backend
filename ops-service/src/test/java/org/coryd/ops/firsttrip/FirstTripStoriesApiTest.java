package org.coryd.ops.firsttrip;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.coryd.common.enums.OpsPermission;
import org.coryd.common.enums.OpsRole;
import org.coryd.common.enums.UserRole;
import org.coryd.common.security.JwtUtil;
import org.coryd.ops.access.OpsAccessPolicy;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GET /ops/first-trip-stories against a stand-in trip-, profile- and identity-service: filters pass through, names are added. */
@SpringBootTest(properties = {
        "spring.kafka.bootstrap-servers=localhost:1",
        "spring.kafka.admin.auto-create=false",
        "services.internal.api-key=test-internal-key",
        "jwt.secret=test-secret-test-secret-test-secret-test-secret-0123456789"
})
@AutoConfigureMockMvc
class FirstTripStoriesApiTest {

    private static final HttpServer UPSTREAM = startUpstream();
    private static final List<String> queries = new CopyOnWriteArrayList<>();

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
        queries.clear();
    }

    @AfterAll
    static void stop() {
        UPSTREAM.stop(0);
    }

    private String bearer(OpsRole role) {
        return "Bearer " + jwtUtil.generateToken("admin-1", null, "Ada", "Admin", UserRole.OPS_ADMIN, role);
    }

    @Test
    void listsStoriesWithNamesAndPassesTheFiltersOn() throws Exception {
        mockMvc.perform(get("/first-trip-stories").param("role", "rider").param("consent", "true").param("size", "500")
                        .header("Authorization", bearer(OpsRole.SUPPORT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.content[0].role").value("RIDER"))
                .andExpect(jsonPath("$.data.content[0].person.name").value("Ada Rider"))
                .andExpect(jsonPath("$.data.content[0].person.ref").exists())
                .andExpect(jsonPath("$.data.content[0].feeling").value("LOVED_IT"))
                .andExpect(jsonPath("$.data.content[0].story").value("So easy"))
                .andExpect(jsonPath("$.data.content[0].consentToShare").value(true))
                .andExpect(jsonPath("$.data.content[0].tripRef").exists())
                .andExpect(jsonPath("$.data.content[1].role").value("DRIVER"))
                .andExpect(jsonPath("$.data.content[1].person.name").value("Bola Driver"))
                .andExpect(jsonPath("$.data.content[1].bookingId").doesNotExist());
        assertThat(queries).anySatisfy(q -> assertThat(q).contains("role=RIDER").contains("consent=true").contains("size=50"));
    }

    @Test
    void anUnknownRoleIsRefused() throws Exception {
        mockMvc.perform(get("/first-trip-stories").param("role", "OPS").header("Authorization", bearer(OpsRole.SUPER_ADMIN)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("role must be DRIVER or RIDER"));
    }

    @Test
    void readOnlyForSafetyViewersAndGrowth() throws Exception {
        assertThat(OpsAccessPolicy.anyOf("GET", "/first-trip-stories")).containsExactlyInAnyOrder(OpsPermission.SAFETY_VIEW, OpsPermission.GROWTH_MANAGE);
        assertThat(OpsAccessPolicy.required("POST", "/first-trip-stories")).isNull();
        mockMvc.perform(get("/first-trip-stories").header("Authorization", bearer(OpsRole.FINANCE))).andExpect(status().isForbidden());
    }

    private static HttpServer startUpstream() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", FirstTripStoriesApiTest::handle);
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String body;
        if (path.endsWith("/internal/ops/first-trip-stories")) {
            queries.add(String.valueOf(exchange.getRequestURI().getQuery()));
            body = "{\"success\":true,\"data\":{\"content\":["
                    + "{\"id\":\"e-1\",\"userId\":\"r-1\",\"role\":\"RIDER\",\"routePostId\":\"8f1c2d3e-0000-0000-0000-000000000001\","
                    + "\"bookingId\":\"b-1\",\"originLabel\":\"Ikeja\",\"destinationLabel\":\"VI\",\"feeling\":\"LOVED_IT\",\"story\":\"So easy\","
                    + "\"consentToShare\":true,\"completedAt\":\"2026-11-20T17:00:00Z\",\"submittedAt\":\"2026-11-20T17:05:00Z\"},"
                    + "{\"id\":\"e-2\",\"userId\":\"d-1\",\"role\":\"DRIVER\",\"routePostId\":\"8f1c2d3e-0000-0000-0000-000000000001\","
                    + "\"bookingId\":null,\"originLabel\":\"Ikeja\",\"destinationLabel\":\"VI\",\"feeling\":\"GOOD\",\"story\":null,"
                    + "\"consentToShare\":false,\"completedAt\":\"2026-11-20T17:00:00Z\",\"submittedAt\":\"2026-11-20T17:01:00Z\"}],"
                    + "\"page\":0,\"size\":50,\"totalElements\":2,\"totalPages\":1,\"last\":true}}";
        } else if (path.endsWith("/internal/riders/batch")) {
            body = "{\"success\":true,\"data\":[{\"userId\":\"r-1\",\"firstName\":\"Ada\",\"lastName\":\"Rider\"}]}";
        } else if (path.endsWith("/internal/drivers/batch")) {
            body = "{\"success\":true,\"data\":[{\"userId\":\"d-1\",\"firstName\":\"Bola\",\"lastName\":\"Driver\"}]}";
        } else {
            body = "{\"success\":true,\"data\":[]}";
        }
        byte[] response = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(response);
        }
    }
}
