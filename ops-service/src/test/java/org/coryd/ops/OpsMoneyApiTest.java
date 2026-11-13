package org.coryd.ops;

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
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 4 money in the Ops Console, against stand-in payment-, trip- and identity-services on a real port: who gets in,
 * what is refused before anything is called, refusals relayed with their own status, the admin's own token passed on, and
 * names, phones and references added to what payment-service returns.
 */
@SpringBootTest(properties = {
        "spring.kafka.bootstrap-servers=localhost:1",
        "spring.kafka.admin.auto-create=false",
        "services.internal.api-key=test-internal-key",
        "jwt.secret=test-secret-test-secret-test-secret-test-secret-0123456789"
})
@AutoConfigureMockMvc
class OpsMoneyApiTest {

    private static final HttpServer UPSTREAM = startUpstream();
    private static final Map<String, Reply> replies = new ConcurrentHashMap<>();
    private static final List<Request> requests = new CopyOnWriteArrayList<>();

    private static final String RIDER = "a11ce000-2222-0000-0000-000000000001";
    private static final String DRIVER = "d0c0ffee-1111-0000-0000-000000000001";
    private static final String BOOKING = "524ea3aa-0000-0000-0000-000000000001";

    private record Reply(int status, String body) {
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
    }

    @Autowired MockMvc mockMvc;
    @Autowired JwtUtil jwtUtil;
    @Autowired ObjectMapper objectMapper;

    @BeforeEach
    void reset() {
        replies.clear();
        requests.clear();
        replies.put("/identity/internal/users/contacts", new Reply(200, "{\"data\":["
                + "{\"userId\":\"" + DRIVER + "\",\"fullName\":\"Emeka Okafor\",\"phone\":\"08053636321\"},"
                + "{\"userId\":\"" + RIDER + "\",\"fullName\":\"Amaka Obi\",\"phone\":\"08011112222\"}]}"));
    }

    @AfterAll
    static void stop() {
        UPSTREAM.stop(0);
    }

    @Test
    void onlyOpsAdminsReachAnyMoneyEndpoint() throws Exception {
        List<RequestBuilder> calls = List.of(
                get("/money/transactions"), get("/money/transactions/counts"), get("/money/payouts"), get("/money/finance/summary"),
                get("/money/pricing"), get("/money/approvals"), get("/money/refund-failures"),
                get("/money/drivers/" + DRIVER + "/payout-account"),
                post("/money/bookings/" + BOOKING + "/refunds").contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"),
                post("/money/approvals/r-1/approve").contentType(MediaType.APPLICATION_JSON).content("{}"),
                post("/money/payouts/p-1/retry").contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"),
                post("/money/drivers/" + DRIVER + "/debt-adjustments").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"direction\":\"DEBIT\",\"amount\":10,\"reason\":\"x\"}"));
        for (RequestBuilder call : calls) {
            mockMvc.perform(call).andExpect(status().isUnauthorized());
        }
        for (UserRole role : List.of(UserRole.RIDER, UserRole.DRIVER)) {
            String token = bearer(role, "user-1");
            mockMvc.perform(get("/money/transactions").header("Authorization", token)).andExpect(status().isForbidden());
            mockMvc.perform(post("/money/bookings/" + BOOKING + "/refunds").header("Authorization", token)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}")).andExpect(status().isForbidden());
            mockMvc.perform(post("/money/approvals/r-1/approve").header("Authorization", token)
                    .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
            mockMvc.perform(post("/money/drivers/" + DRIVER + "/payout-account/reveal").header("Authorization", token)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}")).andExpect(status().isForbidden());
        }
        assertThat(requests).as("a refused caller never reaches payment-service").isEmpty();
    }

    @Test
    void everyMoneyActionNeedsAReasonAndValidInputBeforeAnythingIsCalled() throws Exception {
        String admin = bearer(UserRole.OPS_ADMIN, "ops-ada");
        List<RequestBuilder> bad = List.of(
                post("/money/bookings/" + BOOKING + "/refunds").content("{\"amount\":100}"),
                post("/money/bookings/" + BOOKING + "/refunds").content("{\"amount\":100,\"reason\":\"  \"}"),
                post("/money/bookings/" + BOOKING + "/refunds").content("{\"amount\":-5,\"reason\":\"x\"}"),
                post("/money/approvals/r-1/reject").content("{}"),
                post("/money/approvals/r-1/reject").content("{\"reason\":\" \"}"),
                post("/money/approvals/r-1/retry").content("{}"),
                post("/money/refund-failures/h-1/retry").content("{}"),
                post("/money/payouts/p-1/retry").content("{}"),
                post("/money/drivers/" + DRIVER + "/debt-adjustments").content("{\"amount\":10,\"reason\":\"x\"}"),
                post("/money/drivers/" + DRIVER + "/debt-adjustments").content("{\"direction\":\"SIDEWAYS\",\"amount\":10,\"reason\":\"x\"}"),
                post("/money/drivers/" + DRIVER + "/debt-adjustments").content("{\"direction\":\"DEBIT\",\"amount\":10}"),
                post("/money/drivers/" + DRIVER + "/payout-account/reveal").content("{}"));
        for (RequestBuilder call : bad) {
            var builder = (org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder) call;
            mockMvc.perform(builder.header("Authorization", admin).contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isBadRequest());
        }
        mockMvc.perform(get("/money/transactions").param("type", "WIRE").header("Authorization", admin)).andExpect(status().isBadRequest());
        mockMvc.perform(get("/money/transactions/WIRE/x").header("Authorization", admin)).andExpect(status().isBadRequest());
        mockMvc.perform(get("/money/transactions").param("from", "2026-11-10").param("to", "2026-11-01").header("Authorization", admin))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/money/refund-failures").param("kind", "LOST").header("Authorization", admin)).andExpect(status().isBadRequest());
        mockMvc.perform(post("/money/bookings/" + BOOKING + "/refunds").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"x\"}"))
                .andExpect(status().isOk());
        assertThat(requests).as("only the one valid refund went out").extracting(Request::path)
                .containsExactly("/payment/internal/ops/money/bookings/" + BOOKING + "/refunds");
    }

    @Test
    void aRefusalFromPaymentServiceComesBackWithItsOwnStatusAndMessage() throws Exception {
        replies.put("/payment/internal/ops/money/bookings/" + BOOKING + "/refunds",
                new Reply(400, "{\"success\":false,\"message\":\"Only ₦400 can still be refunded on this booking\"}"));
        replies.put("/payment/internal/ops/money/requests/r-1/approve",
                new Reply(400, "{\"success\":false,\"message\":\"A different admin must approve this - you asked for it\"}"));
        String admin = bearer(UserRole.OPS_ADMIN, "ops-ada");

        mockMvc.perform(post("/money/bookings/" + BOOKING + "/refunds").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":500,\"reason\":\"Overcharged\",\"idempotencyKey\":\"k-1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Only ₦400 can still be refunded on this booking"));
        mockMvc.perform(post("/money/approvals/r-1/approve").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("A different admin must approve this - you asked for it"));

        Request refund = requests.get(0);
        assertThat(refund.auth()).as("the admin's own token is what payment-service checks").isEqualTo(admin);
        assertThat(objectMapper.readTree(refund.body()).get("idempotencyKey").asText()).isEqualTo("k-1");
        assertThat(objectMapper.readTree(refund.body()).get("amount").decimalValue()).isEqualByComparingTo("500");
    }

    @Test
    void anActionsResultAndMessageAreRelayedWithPeopleAdded() throws Exception {
        replies.put("/payment/internal/ops/money/bookings/" + BOOKING + "/refunds", new Reply(200, "{\"success\":true,"
                + "\"message\":\"Above the approval threshold - waiting for a second admin\",\"data\":{\"id\":\"r-9\","
                + "\"status\":\"PENDING_APPROVAL\",\"amount\":60000.00,\"bookingId\":\"" + BOOKING + "\",\"riderId\":\"" + RIDER + "\","
                + "\"driverId\":\"" + DRIVER + "\",\"needsApproval\":true}}"));

        mockMvc.perform(post("/money/bookings/" + BOOKING + "/refunds").header("Authorization", bearer(UserRole.OPS_ADMIN, "ops-ada"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":60000,\"reason\":\"Fraud reversal\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Above the approval threshold - waiting for a second admin"))
                .andExpect(jsonPath("$.data.status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.data.bookingRef").value("B-524EA3"))
                .andExpect(jsonPath("$.data.rider.name").value("Amaka Obi"))
                .andExpect(jsonPath("$.data.driver.ref").value("D-D0C0FF"))
                .andExpect(jsonPath("$.data.driver.phone").value("08053636321"));
    }

    @Test
    void theTransactionsListPassesFiltersOnAndAddsNamesAndReferences() throws Exception {
        replies.put("/payment/internal/ops/money/transactions", new Reply(200, "{\"data\":{\"content\":[{\"type\":\"PAYMENT\",\"id\":\"t-1\","
                + "\"status\":\"PAID\",\"amount\":500.00,\"reference\":\"ref-1\",\"bookingId\":\"" + BOOKING + "\",\"riderId\":\"" + RIDER
                + "\",\"driverId\":\"" + DRIVER + "\"}],\"page\":0,\"size\":20,\"totalElements\":1,\"totalPages\":1,\"last\":true}}"));

        mockMvc.perform(get("/money/transactions").header("Authorization", bearer(UserRole.OPS_ADMIN, "ops-ada"))
                        .param("type", "PAYMENT").param("status", "PAID").param("from", "2026-11-01").param("to", "2026-11-13")
                        .param("bookingId", "B-524EA3").param("amountMin", "100").param("sort", "AMOUNT_DESC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].rider.name").value("Amaka Obi"))
                .andExpect(jsonPath("$.data.content[0].rider.ref").value("R-A11CE0"))
                .andExpect(jsonPath("$.data.content[0].driver.name").value("Emeka Okafor"))
                .andExpect(jsonPath("$.data.content[0].bookingRef").value("B-524EA3"));

        String query = URLDecoder.decode(requests.stream().filter(r -> r.path().endsWith("/transactions")).findFirst().orElseThrow().uri(),
                StandardCharsets.UTF_8);
        assertThat(query).contains("type=PAYMENT", "status=PAID", "from=2026-11-01", "to=2026-11-13", "bookingId=B-524EA3",
                "amountMin=100", "sort=AMOUNT_DESC", "page=0", "size=20");
    }

    @Test
    void aTripFilterBecomesThatTripsBookings() throws Exception {
        String route = "2f2f53aa-0000-0000-0000-000000000001";
        replies.put("/trip/internal/ops/trips", new Reply(200, "{\"data\":{\"content\":[{\"routePostId\":\"" + route + "\"}],"
                + "\"page\":0,\"size\":50,\"totalElements\":1,\"totalPages\":1,\"last\":true}}"));
        replies.put("/trip/internal/ops/trips/" + route, new Reply(200, "{\"data\":{\"trip\":{\"routePostId\":\"" + route + "\"},"
                + "\"passengers\":[{\"bookingId\":\"b-1\"},{\"bookingId\":\"b-2\"}]}}"));
        replies.put("/payment/internal/ops/money/transactions/counts", new Reply(200, "{\"data\":{\"all\":2,\"payment\":2,\"refund\":0,\"payout\":0}}"));

        mockMvc.perform(get("/money/transactions/counts").param("tripId", "T-2F2F53").header("Authorization", bearer(UserRole.OPS_ADMIN, "ops-ada")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.payment").value(2));

        String query = URLDecoder.decode(requests.stream().filter(r -> r.path().endsWith("/counts")).findFirst().orElseThrow().uri(),
                StandardCharsets.UTF_8);
        assertThat(query).contains("bookingIds=b-1", "bookingIds=b-2");
    }

    @Test
    void thePricingMonitorComesFromTripService() throws Exception {
        replies.put("/trip/internal/ops/pricing", new Reply(200, "{\"data\":{\"totals\":{\"routes\":3},\"corridors\":[],"
                + "\"outliers\":[{\"routePostId\":\"2f2f53aa-0000\",\"driverId\":\"" + DRIVER + "\",\"direction\":\"ABOVE\"}],\"riderFareReports\":[]}}"));

        mockMvc.perform(get("/money/pricing").param("corridor", "yaba").param("outliersOnly", "true")
                        .header("Authorization", bearer(UserRole.OPS_ADMIN, "ops-ada")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totals.routes").value(3))
                .andExpect(jsonPath("$.data.outliers[0].tripRef").value("T-2F2F53"))
                .andExpect(jsonPath("$.data.outliers[0].driver.name").value("Emeka Okafor"));
        assertThat(URLDecoder.decode(requests.get(0).uri(), StandardCharsets.UTF_8)).contains("corridor=yaba", "outliersOnly=true");
    }

    private String bearer(UserRole role, String userId) {
        return "Bearer " + jwtUtil.generateToken(userId, "08000000000", "Ada", "Ops", role);
    }

    private static HttpServer startUpstream() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", OpsMoneyApiTest::handle);
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        // The token-revocation bootstrap polls identity-service in the background from startup - not a call any test makes.
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (!path.endsWith("/token-revocations")) {
            requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().toString(), path,
                    exchange.getRequestHeaders().getFirst("Authorization"), body));
        }
        Reply reply = replies.getOrDefault(path, new Reply(200, "{\"success\":true,\"data\":null}"));
        byte[] response = reply.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(reply.status(), response.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(response);
        }
    }
}
