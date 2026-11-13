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
 * Phase 3 - trips and bookings in the Ops Console: the new filters, stuck trips, the actions and the Cancellations Log,
 * against stand-in trip-, profile-, identity- and payment-services on a real port, so calls go out over actual HTTP
 * with the admin's own token.
 */
@SpringBootTest(properties = {
        "spring.kafka.bootstrap-servers=localhost:1",
        "spring.kafka.admin.auto-create=false",
        "services.internal.api-key=test-internal-key",
        "jwt.secret=test-secret-test-secret-test-secret-test-secret-0123456789"
})
@AutoConfigureMockMvc
class OpsTripsAndBookingsApiTest {

    private static final HttpServer UPSTREAM = startUpstream();
    private static final Map<String, Reply> replies = new ConcurrentHashMap<>();
    private static final List<Request> requests = new CopyOnWriteArrayList<>();

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
        replies.put("/profile/internal/drivers/batch", new Reply(200,
                "{\"data\":[{\"userId\":\"d0c0ffee-1111\",\"firstName\":\"Emeka\",\"lastName\":\"Okafor\",\"photoUrl\":\"https://x/d.jpg\"}]}"));
        replies.put("/profile/internal/riders/batch", new Reply(200,
                "{\"data\":[{\"userId\":\"a11ce000-2222\",\"firstName\":\"Amaka\",\"lastName\":\"Obi\"}]}"));
        replies.put("/identity/internal/users/contacts", new Reply(200, "{\"data\":["
                + "{\"userId\":\"d0c0ffee-1111\",\"fullName\":\"Emeka Okafor\",\"phone\":\"08053636321\"},"
                + "{\"userId\":\"a11ce000-2222\",\"fullName\":\"Amaka Obi\",\"phone\":\"08011112222\"}]}"));
    }

    @AfterAll
    static void stop() {
        UPSTREAM.stop(0);
    }

    // ---- who gets in, and what's checked before anything is called -----------------------------------------------

    @Test
    void onlyOpsAdminsReachAnyTripOrBookingEndpoint() throws Exception {
        List<RequestBuilder> calls = List.of(
                get("/trips/stuck"), get("/cancellations"), get("/cancellations/counts"),
                post("/trips/t-1/cancel").contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\",\"fault\":\"DRIVER\"}"),
                post("/trips/t-1/resolve").contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\",\"outcome\":\"COMPLETED\"}"),
                post("/bookings/b-1/cancel").contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\",\"fault\":\"RIDER\"}"));
        for (RequestBuilder call : calls) {
            mockMvc.perform(call).andExpect(status().isUnauthorized());
        }
        for (UserRole role : List.of(UserRole.RIDER, UserRole.DRIVER)) {
            String token = bearer(role);
            mockMvc.perform(get("/trips/stuck").header("Authorization", token)).andExpect(status().isForbidden());
            mockMvc.perform(get("/cancellations").header("Authorization", token)).andExpect(status().isForbidden());
            mockMvc.perform(post("/trips/t-1/cancel").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"reason\":\"x\",\"fault\":\"DRIVER\"}")).andExpect(status().isForbidden());
            mockMvc.perform(post("/trips/t-1/resolve").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"reason\":\"x\",\"outcome\":\"COMPLETED\"}")).andExpect(status().isForbidden());
            mockMvc.perform(post("/bookings/b-1/cancel").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"reason\":\"x\",\"fault\":\"RIDER\"}")).andExpect(status().isForbidden());
        }
        // Only trip-service calls count: the token-revocation bootstrap may poll identity in the background at any time.
        assertThat(requests).as("a refused caller never reaches trip-service").noneMatch(r -> r.path().startsWith("/trip"));
    }

    @Test
    void everyActionNeedsAReasonAndAKnownFaultOrOutcome() throws Exception {
        String admin = bearer(UserRole.OPS_ADMIN);
        mockMvc.perform(post("/trips/t-1/cancel").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fault\":\"DRIVER\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("reason")));
        mockMvc.perform(post("/trips/t-1/cancel").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"  \",\"fault\":\"DRIVER\"}")).andExpect(status().isBadRequest());
        mockMvc.perform(post("/trips/t-1/cancel").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Driver sick\"}")).andExpect(status().isBadRequest());
        mockMvc.perform(post("/trips/t-1/cancel").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Driver sick\",\"fault\":\"WEATHER\"}")).andExpect(status().isBadRequest());
        mockMvc.perform(post("/bookings/b-1/cancel").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"fault\":\"RIDER\"}")).andExpect(status().isBadRequest());
        mockMvc.perform(post("/trips/t-1/resolve").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"outcome\":\"COMPLETED\"}")).andExpect(status().isBadRequest());
        mockMvc.perform(post("/trips/t-1/resolve").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"x\"}")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/cancellations").param("source", "ALIENS").header("Authorization", admin)).andExpect(status().isBadRequest());
        mockMvc.perform(get("/trips").param("from", "13/11/2026").header("Authorization", admin)).andExpect(status().isBadRequest());
        assertThat(requests).as("nothing invalid is passed on").isEmpty();
    }

    // ---- actions -------------------------------------------------------------------------------------------------

    @Test
    void cancellingATripIsSignedByTheAdminAndRelayedWithTheirToken() throws Exception {
        replies.put("/trip/internal/ops/trips/t-1/cancel", new Reply(200, "{\"success\":true,\"data\":{\"action\":\"CANCEL_TRIP\","
                + "\"auditId\":\"audit-1\",\"routePostId\":\"t-1\",\"statusBefore\":\"PUBLISHED\",\"statusAfter\":\"CANCELLED\","
                + "\"refunds\":[{\"bookingId\":\"b-1\",\"riderId\":\"r-1\",\"fareTotal\":1200.00,\"fee\":0,\"refund\":1200.00}],"
                + "\"completed\":[],\"noShows\":[]}}"));
        String token = bearer(UserRole.OPS_ADMIN);

        mockMvc.perform(post("/trips/t-1/cancel").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\" Driver phoned in sick \",\"fault\":\"DRIVER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Trip cancelled"))
                .andExpect(jsonPath("$.data.statusAfter").value("CANCELLED"))
                .andExpect(jsonPath("$.data.refunds[0].refund").value(1200.00));

        Request sent = only("/trip/internal/ops/trips/t-1/cancel");
        assertThat(sent.method()).isEqualTo("POST");
        assertThat(sent.auth()).isEqualTo(token);
        JsonNode body = objectMapper.readTree(sent.body());
        assertThat(body.get("reason").asText()).isEqualTo("Driver phoned in sick");
        assertThat(body.get("fault").asText()).isEqualTo("DRIVER");
        assertThat(body.get("actorId").asText()).isEqualTo("admin-1");
        assertThat(body.get("actorName").asText()).isEqualTo("Ada Ops");
    }

    @Test
    void tripServicesRefusalComesBackAsIs() throws Exception {
        replies.put("/trip/internal/ops/trips/t-done/cancel", new Reply(400,
                "{\"success\":false,\"message\":\"This trip is already completed - it can't be cancelled\"}"));
        replies.put("/trip/internal/ops/trips/t-fresh/resolve", new Reply(400,
                "{\"success\":false,\"message\":\"This trip isn't stuck yet - it counts as stuck 3h after its expected arrival\"}"));
        String token = bearer(UserRole.OPS_ADMIN);

        mockMvc.perform(post("/trips/t-done/cancel").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"x\",\"fault\":\"PLATFORM\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("This trip is already completed - it can't be cancelled"));
        mockMvc.perform(post("/trips/t-fresh/resolve").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"x\",\"outcome\":\"COMPLETED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.startsWith("This trip isn't stuck yet")));
    }

    @Test
    void cancellingABookingAndResolvingATripForwardTheChoices() throws Exception {
        replies.put("/trip/internal/ops/bookings/b-1/cancel", new Reply(200, "{\"data\":{\"action\":\"CANCEL_BOOKING\"}}"));
        replies.put("/trip/internal/ops/trips/t-1/resolve", new Reply(200, "{\"data\":{\"action\":\"RESOLVE_STUCK_COMPLETED\"}}"));
        String token = bearer(UserRole.OPS_ADMIN);

        mockMvc.perform(post("/bookings/b-1/cancel").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Goodwill\",\"fault\":\"RIDER\",\"waiveFee\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("Booking cancelled"));
        JsonNode booking = objectMapper.readTree(only("/trip/internal/ops/bookings/b-1/cancel").body());
        assertThat(booking.get("fault").asText()).isEqualTo("RIDER");
        assertThat(booking.get("waiveFee").asBoolean()).isTrue();

        mockMvc.perform(post("/trips/t-1/resolve").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Riders confirmed by phone\",\"outcome\":\"COMPLETED\",\"unboardedBookings\":\"NO_SHOW\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("Trip marked completed"));
        JsonNode resolve = objectMapper.readTree(only("/trip/internal/ops/trips/t-1/resolve").body());
        assertThat(resolve.get("outcome").asText()).isEqualTo("COMPLETED");
        assertThat(resolve.get("unboardedBookings").asText()).isEqualTo("NO_SHOW");
        assertThat(resolve.has("fault")).isFalse();
    }

    // ---- reads ---------------------------------------------------------------------------------------------------

    @Test
    void theTripsTablePassesEveryFilterAndSortOn() throws Exception {
        replies.put("/trip/internal/ops/trips", new Reply(200, "{\"data\":{\"content\":[" + TRIP_ROW + "],"
                + "\"page\":0,\"size\":20,\"totalElements\":1,\"totalPages\":1,\"last\":true}}"));
        replies.put("/trip/internal/ops/trips/counts", new Reply(200, "{\"data\":{\"all\":9,\"active\":4,\"completed\":3,"
                + "\"cancelled\":2,\"published\":2,\"full\":1,\"inProgress\":1,\"stuck\":1}}"));
        String token = bearer(UserRole.OPS_ADMIN);

        mockMvc.perform(get("/trips").header("Authorization", token).param("status", "ACTIVE").param("routeStatus", "IN_PROGRESS")
                        .param("from", "2026-11-01").param("to", "2026-11-13").param("driverId", "d0c0ffee-1111")
                        .param("riderId", "a11ce000-2222").param("search", "ikorodu").param("sort", "FARE_PER_SEAT")
                        .param("direction", "ASC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].tripRef").value("T-2F2F53"))
                .andExpect(jsonPath("$.data.content[0].driver.name").value("Emeka Okafor"));
        String uri = requests.stream().filter(r -> r.path().equals("/trip/internal/ops/trips")).findFirst().orElseThrow().uri();
        assertThat(uri).contains("status=ACTIVE", "routeStatus=IN_PROGRESS", "from=2026-11-01", "to=2026-11-13",
                "driverId=d0c0ffee-1111", "riderId=a11ce000-2222", "search=ikorodu", "sort=FARE_PER_SEAT", "direction=ASC");

        mockMvc.perform(get("/trips/counts").header("Authorization", token).param("from", "2026-11-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stuck").value(1))
                .andExpect(jsonPath("$.data.inProgress").value(1));
        mockMvc.perform(get("/trips").header("Authorization", token).param("sort", "TOTAL_FARE")).andExpect(status().isBadRequest());
    }

    @Test
    void theTripDetailJoinsPaymentsRefundsAndNamesTheTimeline() throws Exception {
        replies.put("/trip/internal/ops/trips/2f2f5329-2468", new Reply(200, "{\"data\":{\"trip\":" + TRIP_ROW + ","
                + "\"originLat\":6.6,\"originLng\":3.5,\"destinationLat\":6.43,\"destinationLng\":3.42,\"estimatedDurationMinutes\":40,"
                + "\"location\":{\"available\":false},\"lastSeen\":{\"available\":false,\"lat\":6.5,\"lng\":3.4,\"recordedAt\":\"2026-11-13T08:00:00Z\"},"
                + "\"locationAgeSeconds\":900,\"stuck\":true,\"stuckSince\":\"2026-11-13T07:00:00Z\",\"expectedArrivalAt\":\"2026-11-13T04:00:00Z\","
                + "\"postedAt\":\"2026-11-12T10:00:00Z\",\"timeChangeCount\":0,\"actions\":{\"cancel\":true,\"resolve\":true},"
                + "\"passengers\":[{\"bookingId\":\"b0000001-aaaa\",\"riderId\":\"a11ce000-2222\",\"seatCount\":1,\"fareTotal\":1200.00,"
                + "\"status\":\"CANCELLED_RIDER\",\"confirmedAt\":\"2026-11-12T11:00:00Z\",\"cancellable\":false,\"riderFaultFeeNow\":0,"
                + "\"cancellation\":{\"id\":\"c-1\",\"kind\":\"BOOKING\",\"routePostId\":\"2f2f5329-2468\",\"bookingId\":\"b0000001-aaaa\","
                + "\"riderId\":\"a11ce000-2222\",\"driverId\":\"d0c0ffee-1111\",\"source\":\"OPS\",\"actorId\":\"admin-1\",\"actorName\":\"Ada Ops\","
                + "\"reason\":\"Rider asked\",\"fault\":\"RIDER\",\"cancelledAt\":\"2026-11-13T06:00:00Z\",\"minutesBeforeDeparture\":30,"
                + "\"paid\":true,\"fareTotal\":1200.00,\"feeAmount\":300.00,\"refundExpected\":900.00}}],"
                + "\"timeline\":[{\"at\":\"2026-11-12T10:00:00Z\",\"type\":\"POSTED\",\"actor\":\"DRIVER\",\"actorId\":\"d0c0ffee-1111\"},"
                + "{\"at\":\"2026-11-12T11:00:00Z\",\"type\":\"BOOKED\",\"actor\":\"RIDER\",\"actorId\":\"a11ce000-2222\","
                + "\"bookingId\":\"b0000001-aaaa\",\"riderId\":\"a11ce000-2222\",\"detail\":\"Payment succeeded\"}]}}"));
        replies.put("/payment/internal/ops/payments/bookings", new Reply(200, "{\"data\":[{\"bookingId\":\"b0000001-aaaa\","
                + "\"status\":\"PAID\",\"method\":\"PAYSTACK\",\"cardLast4\":\"4081\",\"amountPaid\":1200.00,\"refunded\":0,"
                + "\"refundPending\":900.00,\"netPaid\":1200.00}]}"));

        mockMvc.perform(get("/trips/2f2f5329-2468").header("Authorization", bearer(UserRole.OPS_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stuck").value(true))
                .andExpect(jsonPath("$.data.actions.resolve").value(true))
                .andExpect(jsonPath("$.data.lastSeen.lat").value(6.5))
                .andExpect(jsonPath("$.data.locationAgeSeconds").value(900))
                .andExpect(jsonPath("$.data.passengers[0].name").value("Amaka Obi"))
                .andExpect(jsonPath("$.data.passengers[0].bookingRef").value("B-B00000"))
                .andExpect(jsonPath("$.data.passengers[0].payment.cardLast4").value("4081"))
                .andExpect(jsonPath("$.data.passengers[0].refund.state").value("PENDING"))
                .andExpect(jsonPath("$.data.passengers[0].refund.expected").value(900.00))
                .andExpect(jsonPath("$.data.passengers[0].cancellation.actorName").value("Ada Ops"))
                .andExpect(jsonPath("$.data.passengers[0].cancellation.feeAmount").value(300.00))
                .andExpect(jsonPath("$.data.timeline[0].actorName").value("Emeka Okafor"))
                .andExpect(jsonPath("$.data.timeline[1].riderName").value("Amaka Obi"));
    }

    @Test
    void theCancellationsLogShowsWhereEachRefundIs() throws Exception {
        replies.put("/trip/internal/ops/cancellations", new Reply(200, "{\"data\":{\"content\":["
                + "{\"id\":\"c-route\",\"kind\":\"ROUTE\",\"routePostId\":\"2f2f5329-2468\",\"driverId\":\"d0c0ffee-1111\",\"source\":\"DRIVER\","
                + "\"actorId\":\"d0c0ffee-1111\",\"fault\":\"DRIVER\",\"cancelledAt\":\"2026-11-13T06:00:00Z\",\"minutesBeforeDeparture\":-5,"
                + "\"paid\":true,\"fareTotal\":2400.00,\"feeAmount\":0,\"refundExpected\":2400.00,\"bookingsAffected\":2,"
                + "\"childBookingIds\":[\"b-1\",\"b-2\"]},"
                + "{\"id\":\"c-seat\",\"kind\":\"BOOKING\",\"routePostId\":\"9a9a\",\"bookingId\":\"b-3\",\"riderId\":\"a11ce000-2222\","
                + "\"driverId\":\"d0c0ffee-1111\",\"source\":\"RIDER\",\"actorId\":\"a11ce000-2222\",\"fault\":\"RIDER\","
                + "\"cancelledAt\":\"2026-11-13T05:00:00Z\",\"minutesBeforeDeparture\":45,\"paid\":true,\"fareTotal\":1200.00,"
                + "\"feeAmount\":1200.00,\"refundExpected\":0},"
                + "{\"id\":\"c-unpaid\",\"kind\":\"BOOKING\",\"routePostId\":\"9a9b\",\"bookingId\":\"b-4\",\"source\":\"OPS\","
                + "\"cancelledAt\":\"2026-11-13T04:00:00Z\",\"paid\":true,\"refundExpected\":1200.00}],"
                + "\"page\":0,\"size\":20,\"totalElements\":3,\"totalPages\":1,\"last\":true}}"));
        replies.put("/payment/internal/ops/payments/bookings", new Reply(200, "{\"data\":["
                + "{\"bookingId\":\"b-1\",\"refunded\":1200.00,\"refundPending\":0},"
                + "{\"bookingId\":\"b-2\",\"refunded\":1200.00,\"refundPending\":0},"
                + "{\"bookingId\":\"b-3\",\"refunded\":0,\"refundPending\":0}]}"));
        replies.put("/trip/internal/ops/cancellations/counts", new Reply(200, "{\"data\":{\"all\":3,\"rider\":1,\"driver\":1,"
                + "\"systemExpiry\":0,\"system\":0,\"ops\":1,\"suspension\":0,\"accountDeleted\":0}}"));
        String token = bearer(UserRole.OPS_ADMIN);

        mockMvc.perform(get("/cancellations").header("Authorization", token).param("fault", "DRIVER").param("from", "2026-11-01")
                        .param("kind", "ROUTE").param("search", "ikorodu"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].tripRef").value("T-2F2F53"))
                .andExpect(jsonPath("$.data.content[0].driver.name").value("Emeka Okafor"))
                .andExpect(jsonPath("$.data.content[0].actorName").value("Emeka Okafor"))
                .andExpect(jsonPath("$.data.content[0].refund.state").value("REFUNDED"))
                .andExpect(jsonPath("$.data.content[0].refund.refunded").value(2400.00))
                .andExpect(jsonPath("$.data.content[1].rider.name").value("Amaka Obi"))
                .andExpect(jsonPath("$.data.content[1].actorName").value("Amaka Obi"))
                .andExpect(jsonPath("$.data.content[1].refund.state").value("NO_REFUND_DUE"))
                .andExpect(jsonPath("$.data.content[2].refund.state").value("UNKNOWN"));
        String uri = requests.stream().filter(r -> r.path().equals("/trip/internal/ops/cancellations")).findFirst().orElseThrow().uri();
        assertThat(uri).contains("fault=DRIVER", "from=2026-11-01", "kind=ROUTE", "search=ikorodu");

        mockMvc.perform(get("/cancellations/counts").header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.ops").value(1));
    }

    @Test
    void stuckTripsAndLiveCardsCarryTheLastSeenPoint() throws Exception {
        replies.put("/trip/internal/ops/trips/stuck", new Reply(200, "{\"data\":{\"content\":[{\"trip\":" + TRIP_ROW + ","
                + "\"expectedArrivalAt\":\"2026-11-13T04:00:00Z\",\"stuckSince\":\"2026-11-13T07:00:00Z\",\"minutesPastExpectedArrival\":260,"
                + "\"boarded\":2,\"awaitingPickup\":1,\"completed\":0,"
                + "\"lastSeen\":{\"available\":false,\"lat\":6.5,\"lng\":3.4,\"recordedAt\":\"2026-11-13T05:00:00Z\"},\"locationAgeSeconds\":12000}],"
                + "\"page\":0,\"size\":20,\"totalElements\":1,\"totalPages\":1,\"last\":true}}"));
        replies.put("/trip/internal/ops/trips/live", new Reply(200, "{\"data\":[{\"routePostId\":\"2f2f5329-2468\",\"driverId\":\"d0c0ffee-1111\","
                + "\"phase\":\"ACTIVE\",\"seatsBooked\":1,\"estimatedDurationMinutes\":40,\"elapsedMinutes\":120,\"progress\":1.0,"
                + "\"location\":{\"available\":false},\"lastSeen\":{\"available\":false,\"lat\":6.5,\"lng\":3.4,"
                + "\"recordedAt\":\"2026-11-13T05:00:00Z\"},\"locationAgeSeconds\":600,\"locationStale\":true,\"overdue\":true,\"stuck\":false}]}"));
        String token = bearer(UserRole.OPS_ADMIN);

        mockMvc.perform(get("/trips/stuck").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].trip.tripRef").value("T-2F2F53"))
                .andExpect(jsonPath("$.data.content[0].trip.driver.phone").value("08053636321"))
                .andExpect(jsonPath("$.data.content[0].minutesPastExpectedArrival").value(260))
                .andExpect(jsonPath("$.data.content[0].lastSeen.lat").value(6.5));
        mockMvc.perform(get("/trips/live").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.trips[0].locationStale").value(true))
                .andExpect(jsonPath("$.data.trips[0].locationAgeSeconds").value(600))
                .andExpect(jsonPath("$.data.trips[0].overdue").value(true))
                .andExpect(jsonPath("$.data.trips[0].lastSeen.lng").value(3.4));
    }

    private static final String TRIP_ROW = "{\"routePostId\":\"2f2f5329-2468\",\"driverId\":\"d0c0ffee-1111\",\"vehicleId\":null,"
            + "\"originLabel\":\"Ikorodu\",\"destinationLabel\":\"Victoria Island\",\"departureTime\":\"2026-11-13T03:00:00Z\","
            + "\"status\":\"IN_PROGRESS\",\"seatCapacity\":4,\"seatsAvailable\":1,\"seatsBooked\":3,\"farePerSeat\":1200.00,"
            + "\"totalFare\":3600.00,\"driverRating\":4.8,\"driverRatingCount\":25}";

    private Request only(String path) {
        List<Request> matching = requests.stream().filter(r -> r.path().equals(path)).toList();
        assertThat(matching).hasSize(1);
        return matching.getFirst();
    }

    private String bearer(UserRole role) {
        return "Bearer " + jwtUtil.generateToken("admin-1", "08000000000", "Ada", "Ops", role);
    }

    private static HttpServer startUpstream() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", OpsTripsAndBookingsApiTest::handle);
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().toString(), path,
                exchange.getRequestHeaders().getFirst("Authorization"),
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
