package org.coryd.ops.platform;

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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 6 in the Ops Console against stand-in services on a real port: only ops admins get in, a change without a reason
 * never leaves ops-service, refusals come back with the owning service's status and message, the admin's own token is passed
 * on, the dashboard is put together from three services (and still shows when one is down), and CSV exports stream through.
 */
@SpringBootTest(properties = {
        "spring.kafka.bootstrap-servers=localhost:1",
        "spring.kafka.admin.auto-create=false",
        "services.internal.api-key=test-internal-key",
        "jwt.secret=test-secret-test-secret-test-secret-test-secret-0123456789",
        "ops.dashboard.cache-seconds=60"
})
@AutoConfigureMockMvc
class OpsPlatformApiTest {

    private static final HttpServer UPSTREAM = startUpstream();
    private static final Map<String, Reply> replies = new ConcurrentHashMap<>();
    private static final List<Request> requests = new CopyOnWriteArrayList<>();

    private record Reply(int status, String body, String contentType, Map<String, String> headers) {
        Reply(int status, String body) {
            this(status, body, "application/json", Map.of());
        }
    }

    private record Request(String method, String uri, String path, String auth, String body) {
    }

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
    @Autowired DashboardService dashboardService;

    @BeforeEach
    void reset() {
        replies.clear();
        requests.clear();
        dashboardService.clearCache();
    }

    @AfterAll
    static void stop() {
        UPSTREAM.stop(0);
    }

    @Test
    void onlyOpsAdminsReachAnyPhase6Endpoint() throws Exception {
        List<RequestBuilder> calls = List.of(get("/settings"), get("/settings/commission.percent/history"), get("/dashboard"), get("/corridors"),
                get("/broadcasts"), get("/reports/catalog"), get("/reports/analytics/trips"), get("/reports/analytics/trips.csv"),
                get("/system/health"),
                put("/settings/commission.percent").contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"6\",\"reason\":\"x\"}"),
                post("/broadcasts").contentType(MediaType.APPLICATION_JSON).content("{}"),
                post("/reports/emailed/DAILY/send"));
        for (RequestBuilder call : calls) {
            mockMvc.perform(call).andExpect(status().isUnauthorized());
        }
        for (UserRole role : List.of(UserRole.RIDER, UserRole.DRIVER)) {
            String token = bearer(role, "user-1");
            mockMvc.perform(get("/settings").header("Authorization", token)).andExpect(status().isForbidden());
            mockMvc.perform(get("/dashboard").header("Authorization", token)).andExpect(status().isForbidden());
            mockMvc.perform(get("/system/health").header("Authorization", token)).andExpect(status().isForbidden());
            mockMvc.perform(put("/settings/commission.percent").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"value\":\"6\",\"reason\":\"x\"}")).andExpect(status().isForbidden());
            mockMvc.perform(post("/broadcasts/b-1/cancel").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"reason\":\"x\"}")).andExpect(status().isForbidden());
        }
        assertThat(requests).as("a refused caller never reaches another service").isEmpty();
    }

    @Test
    void aChangeWithoutAReasonOrValueNeverLeavesOpsService() throws Exception {
        String admin = bearer(UserRole.OPS_ADMIN, "ops-ada");
        for (String body : List.of("{\"value\":\"6\"}", "{\"value\":\"6\",\"reason\":\"  \"}", "{\"reason\":\"x\"}", "")) {
            mockMvc.perform(put("/settings/commission.percent").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        mockMvc.perform(post("/settings/commission.percent/reset").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isBadRequest());
        mockMvc.perform(post("/broadcasts").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"t\",\"body\":\"b\",\"channels\":[\"SMS\"],\"audience\":{\"type\":\"ALL_RIDERS\"}}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/broadcasts/b-1/cancel").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/reports/emailed/YEARLY/send").header("Authorization", admin)).andExpect(status().isBadRequest());
        assertThat(requests).isEmpty();
    }

    @Test
    void settingChangesGoToTripServiceWithTheAdminsTokenAndRefusalsComeBackAsTheyAre() throws Exception {
        String admin = bearer(UserRole.OPS_ADMIN, "ops-ada");
        replies.put("/trip/internal/ops/settings/commission.percent", new Reply(400,
                "{\"success\":false,\"message\":\"Platform commission must be between 0 and 25 %\"}"));
        mockMvc.perform(put("/settings/commission.percent").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"40\",\"reason\":\"Board decision\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Platform commission must be between 0 and 25 %"));
        Request sent = requests.get(0);
        assertThat(sent.method()).isEqualTo("PUT");
        assertThat(sent.auth()).isEqualTo(admin);
        assertThat(sent.body()).contains("\"value\":\"40\"").contains("\"reason\":\"Board decision\"");

        replies.put("/trip/internal/ops/settings/commission.percent", new Reply(200,
                "{\"success\":true,\"message\":\"Change scheduled\",\"data\":{\"setting\":{\"key\":\"commission.percent\",\"scheduled\":{\"value\":\"6\"}}}}"));
        mockMvc.perform(put("/settings/commission.percent").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"6\",\"reason\":\"From 1 Dec\",\"effectiveFrom\":\"2026-12-01T00:00:00Z\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Change scheduled"))
                .andExpect(jsonPath("$.data.setting.scheduled.value").value("6"));
        assertThat(requests.get(1).body()).contains("2026-12-01T00:00:00Z");

        replies.put("/trip/internal/ops/settings/nope", new Reply(404, "{\"success\":false,\"message\":\"No setting called 'nope'\"}"));
        mockMvc.perform(get("/settings/nope").header("Authorization", admin)).andExpect(status().isNotFound());
    }

    @Test
    void theDashboardIsPutTogetherFromThreeServicesAndCached() throws Exception {
        String admin = bearer(UserRole.OPS_ADMIN, "ops-ada");
        replies.put("/trip/internal/ops/dashboard", new Reply(200, "{\"data\":{"
                + "\"trips\":{\"posted\":5,\"completed\":2,\"cancelled\":1,\"completionRate\":66.7},"
                + "\"seats\":{\"offered\":17,\"booked\":6},"
                + "\"drivers\":{\"activeInRange\":4,\"onTripNow\":1,\"onlineNow\":2},"
                + "\"riders\":{\"activeInRange\":3,\"repeatInRange\":2},"
                + "\"bookings\":{\"made\":5,\"completed\":3},"
                + "\"cancellationsBySource\":{\"DRIVER\":1},\"cancellationsByKind\":{\"ROUTE\":1},"
                + "\"daily\":[{\"date\":\"2026-11-12\",\"tripsPosted\":0},{\"date\":\"2026-11-13\",\"tripsPosted\":5}],"
                + "\"recentActivity\":[{\"kind\":\"TRIP_POSTED\"}]}}"));
        replies.put("/payment/internal/ops/money/finance/summary", new Reply(200, "{\"data\":{"
                + "\"grossBookings\":{\"count\":4,\"amount\":4800},\"refunds\":{\"count\":0,\"amount\":0},\"netCollected\":4800,"
                + "\"commission\":{\"tripGross\":4800,\"commission\":240,\"driverNet\":4560},"
                + "\"daily\":[{\"date\":\"2026-11-13\",\"grossBookings\":4800,\"payments\":4,\"refunds\":0,\"commission\":240,\"payouts\":0}]}}"));
        replies.put("/profile/internal/ops/pipeline", new Reply(200, "{\"data\":{\"driversTotal\":12,\"driversVerified\":9,"
                + "\"driversSuspended\":1,\"driversActiveLast7Days\":4,\"driverQueue\":{\"PENDING\":2},\"ridersTotal\":40,"
                + "\"ridersSuspended\":0,\"ridersActiveLast7Days\":11,\"riderIdentity\":{\"APPROVED\":30}}}"));

        mockMvc.perform(get("/dashboard").param("from", "2026-11-12").param("to", "2026-11-13").header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.trips.posted").value(5))
                .andExpect(jsonPath("$.data.drivers.total").value(12))
                .andExpect(jsonPath("$.data.drivers.verified").value(9))
                .andExpect(jsonPath("$.data.drivers.onlineNow").value(2))
                .andExpect(jsonPath("$.data.riders.total").value(40))
                .andExpect(jsonPath("$.data.riders.activeInRange").value(3))
                .andExpect(jsonPath("$.data.revenue.grossBookings").value(4800))
                .andExpect(jsonPath("$.data.revenue.commission").value(240))
                .andExpect(jsonPath("$.data.verification.driverQueue.PENDING").value(2))
                .andExpect(jsonPath("$.data.cancellations.bySource.DRIVER").value(1))
                .andExpect(jsonPath("$.data.daily.length()").value(2))
                .andExpect(jsonPath("$.data.daily[1].tripsPosted").value(5))
                .andExpect(jsonPath("$.data.daily[1].commission").value(240))
                .andExpect(jsonPath("$.data.unavailable.length()").value(0));
        assertThat(requests).hasSize(3).allMatch(r -> admin.equals(r.auth()));

        mockMvc.perform(get("/dashboard").param("from", "2026-11-12").param("to", "2026-11-13").header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.cachedAt").isNotEmpty());
        assertThat(requests).as("served from the cache").hasSize(3);

        replies.put("/payment/internal/ops/money/finance/summary", new Reply(503, "{\"message\":\"down\"}"));
        mockMvc.perform(get("/dashboard").param("from", "2026-11-12").param("to", "2026-11-13").param("fresh", "true")
                        .header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.trips.posted").value(5))
                .andExpect(jsonPath("$.data.revenue").isEmpty())
                .andExpect(jsonPath("$.data.unavailable[0]").value("payment-service"));
        mockMvc.perform(get("/dashboard").param("from", "2026-11-13").param("to", "2026-11-12").header("Authorization", admin))
                .andExpect(status().isBadRequest());
    }

    @Test
    void csvExportsStreamThroughWithTheirHeadersAndRevenueIsLaidOutHere() throws Exception {
        String admin = bearer(UserRole.OPS_ADMIN, "ops-ada");
        replies.put("/trip/internal/ops/analytics/trips.csv", new Reply(200, "trip_id,status\r\nt-1,COMPLETED\r\n", "text/csv",
                Map.of("Content-Disposition", "attachment; filename=\"coryd-trips.csv\"", "X-Report-Rows", "1", "X-Report-Truncated", "false")));
        MvcResult csv = mockMvc.perform(get("/reports/analytics/trips.csv").param("from", "2026-11-01").param("to", "2026-11-13")
                        .header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Report-Rows", "1"))
                .andExpect(header().string("X-Report-Truncated", "false"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"coryd-trips.csv\""))
                .andReturn();
        assertThat(csv.getResponse().getContentAsString()).isEqualTo("trip_id,status\r\nt-1,COMPLETED\r\n");
        assertThat(URLDecoder.decode(requests.get(0).uri(), StandardCharsets.UTF_8)).contains("from=2026-11-01", "to=2026-11-13");

        replies.put("/trip/internal/ops/analytics/payroll.csv", new Reply(404, "{\"message\":\"No report called 'payroll'\"}"));
        mockMvc.perform(get("/reports/analytics/payroll.csv").header("Authorization", admin)).andExpect(status().isNotFound());

        replies.put("/payment/internal/ops/money/finance/summary", new Reply(200, "{\"data\":{\"from\":\"2026-11-12\",\"to\":\"2026-11-13\","
                + "\"daily\":[{\"date\":\"2026-11-12\",\"grossBookings\":0,\"payments\":0,\"refunds\":0,\"commission\":0,\"payouts\":0},"
                + "{\"date\":\"2026-11-13\",\"grossBookings\":4800,\"payments\":4,\"refunds\":0,\"commission\":240,\"payouts\":0}]}}"));
        MvcResult revenue = mockMvc.perform(get("/reports/analytics/revenue.csv").header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(header().string("X-Report-Rows", "2")).andReturn();
        assertThat(revenue.getResponse().getContentAsString())
                .startsWith("date,gross_bookings,payments,refunds,commission,payouts\r\n")
                .contains("2026-11-13,4800,4,0,240,0\r\n");
        mockMvc.perform(get("/reports/analytics/revenue").header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.rows.length()").value(2));
    }

    @Test
    void theCatalogListsTripReportsRevenueTheFunnelAndTheEmailedReports() throws Exception {
        replies.put("/trip/internal/ops/analytics", new Reply(200, "{\"data\":[{\"report\":\"trips\",\"title\":\"Trips\",\"columns\":[\"trip_id\"]}]}"));
        mockMvc.perform(get("/reports/catalog").header("Authorization", bearer(UserRole.OPS_ADMIN, "ops-ada")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reports[0].report").value("trips"))
                .andExpect(jsonPath("$.data.reports[1].report").value("revenue"))
                .andExpect(jsonPath("$.data.reports[2].report").value("verification-funnel"))
                .andExpect(jsonPath("$.data.emailed.length()").value(3))
                .andExpect(jsonPath("$.data.maxCsvRows").value(50000));
    }

    @Test
    void broadcastsAndEmailedReportsGoToNotificationService() throws Exception {
        String admin = bearer(UserRole.OPS_ADMIN, "ops-ada");
        replies.put("/notification/internal/ops/broadcasts/preview", new Reply(200, "{\"data\":{\"recipients\":4,\"optedOut\":1}}"));
        mockMvc.perform(post("/broadcasts/preview").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"t\",\"body\":\"b\",\"channels\":[\"SMS\"],\"audience\":{\"type\":\"ALL_RIDERS\"}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.recipients").value(4));
        mockMvc.perform(post("/reports/emailed/weekly/send").param("date", "2026-11-10").header("Authorization", admin))
                .andExpect(status().isOk());
        Request sent = requests.get(1);
        assertThat(sent.path()).isEqualTo("/notification/admin/reports/WEEKLY");
        assertThat(sent.uri()).contains("date=2026-11-10");
        assertThat(sent.auth()).isEqualTo(admin);
    }

    @Test
    void systemHealthReportsAServiceThatIsDownAndAFailedJob() throws Exception {
        replies.put("/trip/actuator/health", new Reply(200, "{\"status\":\"UP\",\"components\":{\"db\":{\"status\":\"UP\"}}}"));
        replies.put("/payment/actuator/health", new Reply(503, "{\"status\":\"DOWN\"}"));
        replies.put("/trip/internal/system/status", new Reply(200, "{\"data\":{\"service\":\"trip-service\",\"jobs\":["
                + "{\"job\":\"RouteExpiryJob.run\",\"schedule\":\"every 300s after the last run\",\"lastRun\":{\"lastStatus\":\"FAILED\","
                + "\"lastError\":\"boom\"}}],\"integrations\":[]}}"));
        replies.put("/notification/internal/system/status", new Reply(200, "{\"data\":{\"jobs\":[],\"integrations\":["
                + "{\"integration\":\"TERMII\",\"operation\":\"sms\",\"attempts\":10,\"failures\":6}]}}"));
        mockMvc.perform(get("/system/health").header("Authorization", bearer(UserRole.OPS_ADMIN, "ops-ada")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.overall").value("DOWN"))
                .andExpect(jsonPath("$.data.services[?(@.service == 'payment-service')].status").value("DOWN"))
                .andExpect(jsonPath("$.data.services[?(@.service == 'trip-service')].components.db").value("UP"))
                .andExpect(jsonPath("$.data.jobs[?(@.job == 'RouteExpiryJob.run')].service").value("trip-service"))
                .andExpect(jsonPath("$.data.integrations[?(@.integration == 'TERMII')].failures").value(6))
                .andExpect(jsonPath("$.data.alerts[?(@.kind == 'JOB')]").isNotEmpty())
                .andExpect(jsonPath("$.data.alerts[?(@.kind == 'INTEGRATION')]").isNotEmpty())
                .andExpect(jsonPath("$.data.kafka.reachable").value(false));
    }

    private String bearer(UserRole role, String userId) {
        return "Bearer " + jwtUtil.generateToken(userId, "08000000000", "Ada", "Ops", role);
    }

    private static HttpServer startUpstream() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", OpsPlatformApiTest::handle);
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        boolean background = path.endsWith("/token-revocations") || path.endsWith("/actuator/health") || path.endsWith("/internal/settings")
                || path.endsWith("/internal/system/status");
        if (!background) {
            requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().toString(), path,
                    exchange.getRequestHeaders().getFirst("Authorization"), body));
        }
        Reply reply = replies.getOrDefault(path, new Reply(200, "{\"success\":true,\"data\":null}"));
        byte[] response = reply.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", reply.contentType());
        reply.headers().forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
        exchange.sendResponseHeaders(reply.status(), response.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(response);
        }
    }
}
