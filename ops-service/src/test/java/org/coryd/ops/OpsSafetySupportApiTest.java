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
import org.springframework.test.web.servlet.RequestBuilder;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 5 - Safety & SOS, Reports & disputes and Ratings in the Ops Console, against stand-in trip-, profile-, identity- and
 * notification-services on a real port: who gets in, what's refused before anything is called, what's signed and passed on,
 * trip-service's refusals passed back as they are, and who-is-who added to every row.
 */
@SpringBootTest(properties = {
        "spring.kafka.bootstrap-servers=localhost:1",
        "spring.kafka.admin.auto-create=false",
        "services.internal.api-key=test-internal-key",
        "jwt.secret=test-secret-test-secret-test-secret-test-secret-0123456789"
})
@AutoConfigureMockMvc
class OpsSafetySupportApiTest {

    private static final HttpServer UPSTREAM = startUpstream();
    private static final Map<String, Reply> replies = new ConcurrentHashMap<>();
    private static final List<Request> requests = new CopyOnWriteArrayList<>();

    private record Reply(int status, String body) {
    }

    private record Request(String method, String uri, String path, String auth, String internalKey, String body) {
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
    @Autowired ObjectMapper objectMapper;

    private static final String SOS_ROW = """
            {"id":"5a5a5a5a-0000-1111-2222-333333333333","kind":"SOS","status":"OPEN","triggeredAt":"2026-11-13T10:00:00Z",
             "triggeredBy":"a11ce000-2222","triggeredByRole":"RIDER","bookingId":"b00b0000-1","riderId":"a11ce000-2222",
             "routePostId":"7a1b0000-9","driverId":"d0c0ffee-1111","vehicleId":"veh-1","originLabel":"Ikorodu",
             "destinationLabel":"Victoria Island","routeStatus":"IN_PROGRESS","latitude":6.5,"longitude":3.4,
             "opsNotifiedAt":"2026-11-13T10:00:01Z","realertsSent":1,"minutesOpen":7}""";

    @BeforeEach
    void reset() {
        replies.clear();
        requests.clear();
        replies.put("/profile/internal/drivers/batch", new Reply(200,
                "{\"data\":[{\"userId\":\"d0c0ffee-1111\",\"firstName\":\"Emeka\",\"lastName\":\"Okafor\",\"photoUrl\":\"https://x/d.jpg\"}]}"));
        replies.put("/profile/internal/riders/batch", new Reply(200,
                "{\"data\":[{\"userId\":\"a11ce000-2222\",\"firstName\":\"Amaka\",\"lastName\":\"Obi\"}]}"));
        replies.put("/profile/internal/vehicles/batch", new Reply(200,
                "{\"data\":[{\"id\":\"veh-1\",\"make\":\"Toyota\",\"model\":\"Camry\",\"year\":2019,\"color\":\"Black\",\"plateNumber\":\"LND-441-AA\"}]}"));
        replies.put("/identity/internal/users/contacts", new Reply(200, "{\"data\":["
                + "{\"userId\":\"d0c0ffee-1111\",\"fullName\":\"Emeka Okafor\",\"phone\":\"08053636321\"},"
                + "{\"userId\":\"a11ce000-2222\",\"fullName\":\"Amaka Obi\",\"phone\":\"08011112222\"}]}"));
    }

    @AfterAll
    static void stop() {
        UPSTREAM.stop(0);
    }

    // ---- who gets in ---------------------------------------------------------------------------------------------

    @Test
    void onlyOpsAdminsReachAnySafetyOrSupportEndpoint() throws Exception {
        List<RequestBuilder> calls = List.of(
                get("/sos"), get("/sos/counts"), get("/sos/s-1"),
                post("/sos/s-1/acknowledge"), json(post("/sos/s-1/notes"), "{\"note\":\"x\"}"),
                json(post("/sos/s-1/escalate"), "{\"target\":\"POLICE\"}"),
                json(post("/sos/s-1/resolve"), "{\"outcome\":\"SAFE\",\"note\":\"x\"}"),
                get("/reports"), get("/reports/counts"), get("/reports/r-1"), post("/reports/r-1/assign"),
                json(post("/reports/r-1/notes"), "{\"note\":\"x\"}"),
                json(post("/reports/r-1/resolve"), "{\"outcome\":\"WARNING\",\"note\":\"x\",\"messageToReporter\":\"y\"}"),
                json(post("/reports/r-1/dismiss"), "{\"note\":\"x\"}"),
                get("/ratings"), get("/ratings/summary/u-1"),
                json(post("/ratings/x/hide"), "{\"reason\":\"x\"}"), json(post("/ratings/x/restore"), "{\"reason\":\"x\"}"),
                post("/stream/token"));
        for (RequestBuilder call : calls) {
            mockMvc.perform(call).andExpect(status().isUnauthorized());
        }
        for (UserRole role : List.of(UserRole.RIDER, UserRole.DRIVER)) {
            String token = bearer(role);
            mockMvc.perform(get("/sos").header("Authorization", token)).andExpect(status().isForbidden());
            mockMvc.perform(post("/sos/s-1/acknowledge").header("Authorization", token)).andExpect(status().isForbidden());
            mockMvc.perform(json(post("/sos/s-1/resolve"), "{\"outcome\":\"SAFE\",\"note\":\"x\"}").header("Authorization", token))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/reports").header("Authorization", token)).andExpect(status().isForbidden());
            mockMvc.perform(json(post("/reports/r-1/dismiss"), "{\"note\":\"x\"}").header("Authorization", token))
                    .andExpect(status().isForbidden());
            mockMvc.perform(json(post("/ratings/x/hide"), "{\"reason\":\"x\"}").header("Authorization", token))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post("/stream/token").header("Authorization", token)).andExpect(status().isForbidden());
        }
        assertThat(requests).as("a refused caller never reaches another service").isEmpty();
    }

    // ---- refused before anything is called -----------------------------------------------------------------------

    @Test
    void missingNotesOutcomesReasonsAndBadFiltersAre400() throws Exception {
        String admin = bearer(UserRole.OPS_ADMIN);
        List<RequestBuilder> bad = List.of(
                json(post("/sos/s-1/notes"), "{}"),
                json(post("/sos/s-1/notes"), "{\"note\":\"   \"}"),
                json(post("/sos/s-1/escalate"), "{\"contact\":\"Sgt Bello\"}"),
                json(post("/sos/s-1/escalate"), "{\"target\":\"BATMAN\"}"),
                json(post("/sos/s-1/resolve"), "{\"note\":\"all fine\"}"),
                json(post("/sos/s-1/resolve"), "{\"outcome\":\"SAFE\"}"),
                json(post("/sos/s-1/resolve"), "{\"outcome\":\"MAYBE\",\"note\":\"x\"}"),
                json(post("/reports/r-1/notes"), "{}"),
                json(post("/reports/r-1/resolve"), "{\"note\":\"x\",\"messageToReporter\":\"y\"}"),
                json(post("/reports/r-1/resolve"), "{\"outcome\":\"WARNING\",\"messageToReporter\":\"y\"}"),
                json(post("/reports/r-1/resolve"), "{\"outcome\":\"WARNING\",\"note\":\"x\"}"),
                json(post("/reports/r-1/dismiss"), "{}"),
                json(post("/ratings/x/hide"), "{}"),
                json(post("/ratings/x/restore"), "{\"reason\":\"\"}"),
                get("/sos").param("status", "PANIC"),
                get("/sos").param("from", "13/11/2026"),
                get("/reports").param("reporterRole", "ADMIN"),
                get("/reports").param("category", "ALIENS"),
                get("/ratings").param("maxStars", "9"));
        for (RequestBuilder call : bad) {
            mockMvc.perform(withAuth(call, admin)).andExpect(status().isBadRequest());
        }
        mockMvc.perform(json(post("/sos/s-1/resolve"), "{\"note\":\"x\"}").header("Authorization", admin))
                .andExpect(jsonPath("$.message").value(containsString("outcome")));
        mockMvc.perform(json(post("/reports/r-1/resolve"), "{\"outcome\":\"WARNING\",\"note\":\"x\"}").header("Authorization", admin))
                .andExpect(jsonPath("$.message").value(containsString("messageToReporter")));
        assertThat(requests).as("nothing invalid is passed on: %s", requests).isEmpty();
    }

    // ---- passed on, signed, and enriched -------------------------------------------------------------------------

    @Test
    void theQueueComesBackWithWhoEveryoneIsAndFiltersPassedOn() throws Exception {
        replies.put("/trip/internal/ops/sos/queue", new Reply(200,
                "{\"data\":{\"content\":[" + SOS_ROW + "],\"page\":0,\"size\":20,\"totalElements\":1,\"totalPages\":1,\"last\":true}}"));
        String body = mockMvc.perform(get("/sos").param("status", "OPEN").param("driverId", "d0c0ffee-1111").param("from", "2026-11-13")
                        .header("Authorization", bearer(UserRole.OPS_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].ref").value("S-5A5A5A"))
                .andExpect(jsonPath("$.data.content[0].raisedBy.name").value("Amaka Obi"))
                .andExpect(jsonPath("$.data.content[0].raisedBy.phone").value("08011112222"))
                .andExpect(jsonPath("$.data.content[0].raisedBy.role").value("RIDER"))
                .andExpect(jsonPath("$.data.content[0].driver.name").value("Emeka Okafor"))
                .andExpect(jsonPath("$.data.content[0].driver.phone").value("08053636321"))
                .andExpect(jsonPath("$.data.content[0].vehicle.plateNumber").value("LND-441-AA"))
                .andExpect(jsonPath("$.data.content[0].tripRef").value("T-7A1B00"))
                .andExpect(jsonPath("$.data.content[0].minutesOpen").value(7))
                .andExpect(jsonPath("$.data.content[0].realertsSent").value(1))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains("\"acknowledgedBy\":null");
        Request queue = requests.stream().filter(r -> r.path().equals("/trip/internal/ops/sos/queue")).findFirst().orElseThrow();
        assertThat(queue.uri()).contains("status=OPEN").contains("driverId=d0c0ffee-1111").contains("from=2026-11-13").contains("size=20");
        assertThat(queue.auth()).as("the admin's own token goes on").startsWith("Bearer ");
        assertThat(queue.internalKey()).isEqualTo("test-internal-key");
    }

    @Test
    void actionsAreSignedWithTheAdminAndTripServicesRefusalComesBackAsIs() throws Exception {
        replies.put("/trip/internal/ops/sos/s-1/acknowledge", new Reply(200, "{\"data\":{\"action\":\"SOS_ACKNOWLEDGED\",\"auditId\":\"a-1\","
                + "\"statusBefore\":\"OPEN\",\"statusAfter\":\"ACKNOWLEDGED\",\"incident\":" + SOS_ROW.replace("\"status\":\"OPEN\"",
                "\"status\":\"ACKNOWLEDGED\",\"acknowledgedAt\":\"2026-11-13T10:02:00Z\",\"acknowledgedBy\":\"admin-1\",\"acknowledgedByName\":\"Ada Ops\"") + "}}"));
        mockMvc.perform(post("/sos/s-1/acknowledge").header("Authorization", bearer(UserRole.OPS_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("SOS acknowledged"))
                .andExpect(jsonPath("$.data.statusAfter").value("ACKNOWLEDGED"))
                .andExpect(jsonPath("$.data.incident.acknowledgedBy.name").value("Ada Ops"));
        JsonNode sent = objectMapper.readTree(requests.stream().filter(r -> r.path().endsWith("/acknowledge")).findFirst().orElseThrow().body());
        assertThat(sent.get("actorId").asText()).isEqualTo("admin-1");
        assertThat(sent.get("actorName").asText()).isEqualTo("Ada Ops");

        replies.put("/trip/internal/ops/sos/s-1/resolve", new Reply(400,
                "{\"success\":false,\"message\":\"This SOS is already resolved (SAFE by Bola)\"}"));
        mockMvc.perform(json(post("/sos/s-1/resolve"), "{\"outcome\":\"SAFE\",\"note\":\"twice\"}").header("Authorization", bearer(UserRole.OPS_ADMIN)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("This SOS is already resolved (SAFE by Bola)"));
        JsonNode resolve = objectMapper.readTree(requests.stream().filter(r -> r.path().endsWith("/resolve")).findFirst().orElseThrow().body());
        assertThat(resolve.get("outcome").asText()).isEqualTo("SAFE");
        assertThat(resolve.get("note").asText()).isEqualTo("twice");

        replies.put("/trip/internal/ops/reports/r-1/resolve", new Reply(400,
                "{\"success\":false,\"message\":\"This report is already resolved - it can't be resolved\"}"));
        mockMvc.perform(json(post("/reports/r-1/resolve"), "{\"outcome\":\"SUSPENDED\",\"note\":\"x\",\"messageToReporter\":\"y\","
                        + "\"actionType\":\"SUSPENSION\",\"actionReference\":\"d0c0ffee-1111\"}").header("Authorization", bearer(UserRole.OPS_ADMIN)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("already resolved")));
        JsonNode report = objectMapper.readTree(requests.stream().filter(r -> r.path().equals("/trip/internal/ops/reports/r-1/resolve"))
                .findFirst().orElseThrow().body());
        assertThat(report.get("actionType").asText()).isEqualTo("SUSPENSION");
        assertThat(report.get("actionReference").asText()).isEqualTo("d0c0ffee-1111");
        assertThat(report.get("actorId").asText()).isEqualTo("admin-1");
    }

    @Test
    void theDetailShowsWhoWasTextedOnWhichChannelAndTheIncidentsOwnTimeline() throws Exception {
        replies.put("/trip/internal/ops/sos/s-1", new Reply(200, "{\"data\":{\"incident\":" + SOS_ROW + ","
                + "\"contactsNotified\":[{\"name\":\"Mum\",\"phone\":\"08011110000\"}],"
                + "\"safetyCheck\":null,\"tripSafetyChecks\":[{\"alertId\":\"al-1\",\"kind\":\"STOPPED\",\"status\":\"HELP\","
                + "\"raisedAt\":\"2026-11-13T09:55:00Z\",\"answers\":[{\"userId\":\"d0c0ffee-1111\",\"role\":\"DRIVER\",\"answer\":\"WAITING\"},"
                + "{\"userId\":\"a11ce000-2222\",\"role\":\"RIDER\",\"answer\":\"HELP\"}]}],"
                + "\"realerts\":[{\"level\":1,\"minutesOpen\":5,\"at\":\"2026-11-13T10:05:00Z\"}],"
                + "\"actions\":[],\"allowed\":{\"acknowledge\":true,\"note\":true,\"escalate\":true,\"resolve\":true}}}"));
        replies.put("/trip/internal/ops/trips/7a1b0000-9", new Reply(503, "{\"success\":false,\"message\":\"down\"}"));
        Instant at = Instant.parse("2026-11-13T10:00:02Z");
        replies.put("/notification/internal/ops/deliveries", new Reply(200, "{\"data\":["
                + "{\"at\":\"" + at + "\",\"eventType\":\"SOS_TRIGGERED\",\"channel\":\"WHATSAPP\",\"recipientPhone\":\"2348011110000\",\"delivered\":false,\"errorCode\":\"HTTP_500\"},"
                + "{\"at\":\"" + at + "\",\"eventType\":\"SOS_TRIGGERED\",\"channel\":\"SMS\",\"recipientPhone\":\"08011110000\",\"delivered\":true},"
                + "{\"at\":\"" + at + "\",\"eventType\":\"SOS_OPS_ALERT\",\"channel\":\"EMAIL\",\"recipientEmail\":\"ops@coryd.ng\",\"delivered\":true},"
                + "{\"at\":\"" + at + "\",\"eventType\":\"SOS_TRIGGERED\",\"channel\":\"SMS\",\"recipientPhone\":\"08099999999\",\"delivered\":true}]}"));

        mockMvc.perform(get("/sos/s-1").header("Authorization", bearer(UserRole.OPS_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.incident.raisedBy.name").value("Amaka Obi"))
                .andExpect(jsonPath("$.data.contactsNotified[0].name").value("Mum"))
                .andExpect(jsonPath("$.data.contactsNotified[0].deliveries.length()").value(2))
                .andExpect(jsonPath("$.data.contactsNotified[0].deliveries[0].channel").value("WHATSAPP"))
                .andExpect(jsonPath("$.data.contactsNotified[0].deliveries[1].channel").value("SMS"))
                .andExpect(jsonPath("$.data.contactsNotified[0].deliveries[1].delivered").value(true))
                .andExpect(jsonPath("$.data.opsAlerts[0].recipient").value("ops@coryd.ng"))
                .andExpect(jsonPath("$.data.tripSafetyChecks[0].answers[1].name").value("Amaka Obi"))
                .andExpect(jsonPath("$.data.timeline[0].type").value("SAFETY_CHECK"))
                .andExpect(jsonPath("$.data.timeline[1].type").value("SOS_RAISED"))
                .andExpect(jsonPath("$.data.timeline[2].type").value("REALERT"))
                .andExpect(jsonPath("$.data.trip").doesNotExist())
                .andExpect(jsonPath("$.data.allowed.acknowledge").value(true));
        Request deliveries = requests.stream().filter(r -> r.path().equals("/notification/internal/ops/deliveries")).findFirst().orElseThrow();
        assertThat(deliveries.uri()).contains("eventTypes=SOS_TRIGGERED").contains("SOS_OPS_REALERT");
        assertThat(deliveries.internalKey()).isEqualTo("test-internal-key");
    }

    @Test
    void aReportsDetailTagsPriorReportsWithWhoTheyInvolveAndOffersTheExistingActions() throws Exception {
        String row = """
                {"id":"r-1","referenceNumber":"ISS-00000001","status":"OPEN","category":"DRIVER_CONDUCT","description":"Rude",
                 "reporterId":"a11ce000-2222","reporterRole":"RIDER","bookingId":"b00b0000-1","riderId":"a11ce000-2222",
                 "routePostId":"7a1b0000-9","driverId":"d0c0ffee-1111","createdAt":"2026-11-13T10:00:00Z"}""";
        String prior = """
                {"id":"r-0","referenceNumber":"ISS-00000000","status":"RESOLVED","category":"DRIVER_CONDUCT","description":"Also rude",
                 "reporterId":"someone","reporterRole":"RIDER","bookingId":"b-2","riderId":"someone","routePostId":"t-2",
                 "driverId":"d0c0ffee-1111","createdAt":"2026-11-01T10:00:00Z","outcome":"WARNING"}""";
        replies.put("/trip/internal/ops/reports/r-1", new Reply(200, "{\"data\":{\"report\":" + row + ",\"booking\":{\"bookingId\":\"b00b0000-1\","
                + "\"status\":\"COMPLETED\",\"seatCount\":1,\"fareTotal\":1200},\"attachments\":[],\"priorReports\":[" + prior + "],"
                + "\"actions\":[],\"allowed\":{\"assign\":true,\"note\":true,\"resolve\":true,\"dismiss\":true}}}"));

        mockMvc.perform(get("/reports/r-1").header("Authorization", bearer(UserRole.OPS_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.report.reporter.name").value("Amaka Obi"))
                .andExpect(jsonPath("$.data.report.driver.phone").value("08053636321"))
                .andExpect(jsonPath("$.data.priorReports[0].involves[0]").value("d0c0ffee-1111"))
                .andExpect(jsonPath("$.data.priorReportCounts['d0c0ffee-1111']").value(1))
                .andExpect(jsonPath("$.data.priorReportCounts['a11ce000-2222']").value(0))
                .andExpect(jsonPath("$.data.relatedActions[0].path").value("/bookings/b00b0000-1/cancel"))
                .andExpect(jsonPath("$.data.relatedActions[1].path").value("/users/d0c0ffee-1111/suspend"))
                .andExpect(jsonPath("$.data.booking.bookingRef").value("B-B00B00"));
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder json(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder, String body) {
        return builder.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static RequestBuilder withAuth(RequestBuilder call, String token) {
        return ((org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder) call).header("Authorization", token);
    }

    private String bearer(UserRole role) {
        return "Bearer " + jwtUtil.generateToken("admin-1", "08000000000", "Ada", "Ops", role);
    }

    private static HttpServer startUpstream() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", OpsSafetySupportApiTest::handle);
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.endsWith("/token-revocations")) {
            // common's revocation registry loading its snapshot at startup - not something these tests send
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
            return;
        }
        requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().toString(), path,
                exchange.getRequestHeaders().getFirst("Authorization"), exchange.getRequestHeaders().getFirst("X-Internal-Api-Key"),
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
        Reply reply = replies.getOrDefault(path, new Reply(200, "{\"success\":true,\"data\":null}"));
        byte[] response = reply.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(reply.status(), response.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(response);
        }
    }
}
