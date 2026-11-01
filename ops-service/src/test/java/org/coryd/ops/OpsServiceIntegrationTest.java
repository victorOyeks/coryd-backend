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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
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
    /** Per-path replies (status, body) - anything not listed falls back to nextStatus/nextBody. */
    private static final Map<String, Reply> replies = new ConcurrentHashMap<>();
    private static final List<String> paths = new CopyOnWriteArrayList<>();
    private static final List<Request> requests = new CopyOnWriteArrayList<>();

    private record Reply(int status, String body) {
    }

    private record Request(String method, String path, String body) {
    }

    @DynamicPropertySource
    static void upstreamUrls(DynamicPropertyRegistry registry) {
        String base = "http://localhost:" + PROFILE.getAddress().getPort();
        registry.add("services.profile.url", () -> base + "/profile");
        registry.add("services.trip.url", () -> base + "/trip");
        registry.add("services.identity.url", () -> base + "/identity");
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
        replies.clear();
        paths.clear();
        requests.clear();
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
    void queueIsPagedWithPhoneAndRefAndRelayedWithTheAdminsOwnToken() throws Exception {
        replies.put("/profile/drivers/verification/queue", new Reply(200, "{\"success\":true,\"data\":{\"content\":[{"
                + "\"driverId\":\"4f2a91c0-aaaa\",\"firstName\":\"Tunde\",\"lastName\":\"Bakare\",\"tab\":\"RESUBMITTED\","
                + "\"waitingSince\":\"2026-10-03T08:00:00Z\",\"items\":[{\"item\":\"VEHICLE\",\"status\":\"IN_REVIEW\","
                + "\"submittedAt\":\"2026-10-03T08:00:00Z\",\"resubmitted\":true}]}],"
                + "\"page\":0,\"size\":20,\"totalElements\":1,\"totalPages\":1,\"last\":true}}"));
        replies.put("/identity/internal/users/contacts", new Reply(200,
                "{\"data\":[{\"userId\":\"4f2a91c0-aaaa\",\"fullName\":\"Tunde Bakare\",\"phone\":\"08011112222\"}]}"));
        String token = bearer(UserRole.OPS_ADMIN);

        mockMvc.perform(get("/drivers/verification/queue?tab=RESUBMITTED&search=tunde").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].driver.ref").value("D-4F2A91"))
                .andExpect(jsonPath("$.data.content[0].driver.name").value("Tunde Bakare"))
                .andExpect(jsonPath("$.data.content[0].driver.phone").value("08011112222"))
                .andExpect(jsonPath("$.data.content[0].tab").value("RESUBMITTED"))
                .andExpect(jsonPath("$.data.content[0].items[0].resubmitted").value(true))
                .andExpect(jsonPath("$.data.totalElements").value(1));

        assertThat(paths).anyMatch(p -> p.startsWith("/profile/drivers/verification/queue?") && p.contains("tab=RESUBMITTED")
                && p.contains("search=tunde"));
        assertThat(lastAuth.get()).isEqualTo(token);
    }

    @Test
    void queueTabCountsAndUnknownTab() throws Exception {
        replies.put("/profile/drivers/verification/queue/counts",
                new Reply(200, "{\"data\":{\"all\":9,\"pending\":4,\"inReview\":3,\"resubmitted\":2}}"));

        mockMvc.perform(get("/drivers/verification/queue/counts").header("Authorization", bearer(UserRole.OPS_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resubmitted").value(2))
                .andExpect(jsonPath("$.data.inReview").value(3));
        mockMvc.perform(get("/drivers/verification/queue?tab=DONE").header("Authorization", bearer(UserRole.OPS_ADMIN)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void reviewPageAddsTheDriversPhoneAndRef() throws Exception {
        replies.put("/profile/drivers/d-1/verification-status", new Reply(200, "{\"data\":{\"driver\":{\"firstName\":\"Ada\","
                + "\"lastName\":\"Obi\",\"licenceNumber\":\"LAG12345678\",\"dateOfBirth\":\"1990-01-01\"},"
                + "\"governmentId\":{\"status\":\"IN_REVIEW\",\"submittedAt\":\"2026-10-03T08:00:00Z\",\"resubmitted\":false},"
                + "\"overallStatus\":\"IN_REVIEW\",\"documents\":[],\"providerChecks\":[]}}"));
        replies.put("/identity/internal/users/contacts", new Reply(200,
                "{\"data\":[{\"userId\":\"d-1\",\"fullName\":\"Ada Obi\",\"phone\":\"08033334444\"}]}"));

        mockMvc.perform(get("/drivers/d-1/verification").header("Authorization", bearer(UserRole.OPS_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.driver.phone").value("08033334444"))
                .andExpect(jsonPath("$.data.driver.ref").value("D-D1"))
                .andExpect(jsonPath("$.data.driver.licenceNumber").value("LAG12345678"))
                .andExpect(jsonPath("$.data.governmentId.submittedAt").value("2026-10-03T08:00:00Z"));
    }

    @Test
    void decisionGoesOutAsPatchSignedByTheAdmin() throws Exception {
        mockMvc.perform(post("/drivers/d-1/verification/decisions").header("Authorization", bearer(UserRole.OPS_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decisions\":[{\"item\":\"VEHICLE\",\"status\":\"APPROVED\"}],\"reviewedBy\":\"someone-else\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.overallStatus").value("IN_REVIEW"));

        Request patch = requests.stream().filter(r -> r.method().equals("PATCH")).findFirst().orElseThrow();
        assertThat(patch.path()).isEqualTo("/profile/drivers/d-1/verification");
        JsonNode sent = objectMapper.readTree(patch.body());
        assertThat(sent.get("decisions").get(0).get("item").asText()).isEqualTo("VEHICLE");
        assertThat(sent.get("reviewedBy").asText()).as("can't be written in someone else's name").isEqualTo("Ada Ops");
    }

    @Test
    void malformedDecisionIsRejectedBeforeReachingProfileService() throws Exception {
        mockMvc.perform(post("/drivers/d-1/verification/decisions").header("Authorization", bearer(UserRole.OPS_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decisions\":[]}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/drivers/d-1/verification/decisions").header("Authorization", bearer(UserRole.OPS_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decisions\":[{\"item\":\"VEHICLE\"}]}"))
                .andExpect(status().isBadRequest());
        assertThat(lastPath.get()).isNull();
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

    // ---- review screen: marks, approve / reject, audit log, files ---------------------------------

    private static final String STATUS_JSON = "{\"success\":true,\"data\":{\"driver\":{\"firstName\":\"Emeka\",\"lastName\":\"Okafor\"},"
            + "\"governmentId\":{\"status\":\"IN_REVIEW\",\"url\":\"https://res.cloudinary.com/c/image/upload/v1/licence.jpg\","
            + "\"submittedAt\":\"2026-10-03T08:00:00Z\",\"resubmitted\":false},"
            + "\"vehicleRegistration\":{\"status\":\"IN_REVIEW\",\"vehicle\":{\"make\":\"Toyota\",\"model\":\"Corolla\",\"year\":2025,"
            + "\"color\":\"Black\",\"plateNumber\":\"AGL-354-RF\",\"exteriorPhotoUrl\":\"https://res.cloudinary.com/c/image/upload/v1/ext.jpg\","
            + "\"interiorPhotoUrl\":null,\"hasAC\":true,\"acConfirmedWorking\":true},\"submittedAt\":\"2026-10-03T08:00:00Z\",\"resubmitted\":false},"
            + "\"documents\":[{\"type\":\"INSURANCE\",\"status\":\"IN_REVIEW\",\"expiresOn\":\"2026-12-09\","
            + "\"documentUrl\":\"https://res.cloudinary.com/c/raw/upload/v1/insurance.pdf\",\"submittedAt\":\"2026-10-03T08:00:00Z\",\"resubmitted\":false}],"
            + "\"overallStatus\":\"IN_REVIEW\",\"providerChecks\":[{\"type\":\"DRIVERS_LICENCE\",\"outcome\":\"NAME_MISMATCH\","
            + "\"summary\":\"Name differs\",\"providerReference\":\"job-123\"}]}}";
    private static final String REVIEW_JSON = "{\"success\":true,\"data\":{\"items\":[{\"item\":\"VEHICLE\",\"label\":\"Vehicle registration\","
            + "\"status\":\"IN_REVIEW\",\"mark\":\"FLAGGED\",\"note\":\"Blurry document\",\"markedBy\":\"Ada Ops\"}],"
            + "\"canApprove\":false,\"approveBlockedReason\":\"Flagged: Vehicle registration - reject, or reset the flag\",\"canReject\":true}}";

    @Test
    void queueForwardsTheSortAndEachRowCarriesItsSubmittedTimeAndDocumentLabels() throws Exception {
        replies.put("/profile/drivers/verification/queue", new Reply(200, "{\"success\":true,\"data\":{\"content\":[{"
                + "\"driverId\":\"4f2a91c0-aaaa\",\"firstName\":\"Tunde\",\"lastName\":\"Bakare\",\"tab\":\"PENDING\","
                + "\"waitingSince\":\"2026-10-03T08:00:00Z\",\"items\":[{\"item\":\"GOVERNMENT_ID\",\"status\":\"IN_REVIEW\","
                + "\"submittedAt\":\"2026-10-02T17:10:00Z\",\"resubmitted\":false},{\"item\":\"ROADWORTHINESS_CERT\",\"status\":\"PENDING\","
                + "\"submittedAt\":null,\"resubmitted\":false}]}],\"page\":0,\"size\":10,\"totalElements\":1,\"totalPages\":1,\"last\":true}}"));

        mockMvc.perform(get("/drivers/verification/queue?sort=NAME&direction=DESC&size=10").header("Authorization", bearer(UserRole.OPS_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].submittedAt").value("2026-10-02T17:10:00Z"))
                .andExpect(jsonPath("$.data.content[0].items[0].label").value("Driver's licence"))
                .andExpect(jsonPath("$.data.content[0].items[1].label").value("Roadworthiness certificate"));

        assertThat(paths).anyMatch(p -> p.startsWith("/profile/drivers/verification/queue?") && p.contains("sort=NAME") && p.contains("direction=DESC"));
        mockMvc.perform(get("/drivers/verification/queue?sort=COLOUR").header("Authorization", bearer(UserRole.OPS_ADMIN)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void theReviewPageCarriesTheMarksFilesInsuranceExpiryAndSmileIdJob() throws Exception {
        replies.put("/profile/drivers/d-1/verification-status", new Reply(200, STATUS_JSON));
        replies.put("/profile/drivers/d-1/verification/review", new Reply(200, REVIEW_JSON));

        mockMvc.perform(get("/drivers/d-1/verification").header("Authorization", bearer(UserRole.OPS_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.review.items[0].mark").value("FLAGGED"))
                .andExpect(jsonPath("$.data.review.items[0].note").value("Blurry document"))
                .andExpect(jsonPath("$.data.review.canApprove").value(false))
                .andExpect(jsonPath("$.data.review.canReject").value(true))
                .andExpect(jsonPath("$.data.review.approveBlockedReason").value("Flagged: Vehicle registration - reject, or reset the flag"))
                .andExpect(jsonPath("$.data.governmentId.file.fileType").value("IMAGE"))
                .andExpect(jsonPath("$.data.governmentId.file.downloadUrl").value("https://res.cloudinary.com/c/image/upload/fl_attachment/v1/licence.jpg"))
                .andExpect(jsonPath("$.data.vehicleRegistration.vehicle.exteriorPhoto.fileType").value("IMAGE"))
                .andExpect(jsonPath("$.data.vehicleRegistration.vehicle.interiorPhoto").doesNotExist())
                .andExpect(jsonPath("$.data.documents[0].file.fileType").value("PDF"))
                .andExpect(jsonPath("$.data.documents[0].file.downloadUrl").value("https://res.cloudinary.com/c/raw/upload/v1/insurance.pdf"))
                .andExpect(jsonPath("$.data.insuranceExpiresOn").value("2026-12-09"))
                .andExpect(jsonPath("$.data.providerChecks[0].providerReference").value("job-123"));
    }

    @Test
    void theReviewPageStillShowsWhenTheMarksCantBeLoaded() throws Exception {
        replies.put("/profile/drivers/d-1/verification-status", new Reply(200, STATUS_JSON));
        replies.put("/profile/drivers/d-1/verification/review", new Reply(500, "{\"success\":false,\"message\":\"boom\"}"));

        mockMvc.perform(get("/drivers/d-1/verification").header("Authorization", bearer(UserRole.OPS_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.overallStatus").value("IN_REVIEW"))
                .andExpect(jsonPath("$.data.review").doesNotExist());
    }

    @Test
    void flaggingADocumentGoesOutSignedByTheAdminAndReturnsTheScreen() throws Exception {
        replies.put("/profile/drivers/d-1/verification-status", new Reply(200, STATUS_JSON));
        replies.put("/profile/drivers/d-1/verification/items/VEHICLE/review", new Reply(200, REVIEW_JSON));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/drivers/d-1/verification/items/VEHICLE/review")
                        .header("Authorization", bearer(UserRole.OPS_ADMIN)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mark\":\"FLAGGED\",\"note\":\"Blurry document\",\"markedBy\":\"someone-else\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.review.items[0].mark").value("FLAGGED"))
                .andExpect(jsonPath("$.data.vehicleRegistration.vehicle.make").value("Toyota"));

        Request put = requests.stream().filter(r -> r.method().equals("PUT")).findFirst().orElseThrow();
        JsonNode sent = objectMapper.readTree(put.body());
        assertThat(sent.get("mark").asText()).isEqualTo("FLAGGED");
        assertThat(sent.get("note").asText()).isEqualTo("Blurry document");
        assertThat(sent.get("markedBy").asText()).as("always the signed-in admin").isEqualTo("Ada Ops");
    }

    @Test
    void aMarkWithoutAValueOrForAnUnknownDocumentNeverReachesProfileService() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/drivers/d-1/verification/items/VEHICLE/review")
                        .header("Authorization", bearer(UserRole.OPS_ADMIN)).contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"x\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/drivers/d-1/verification/items/NATIONAL_ID/review")
                        .header("Authorization", bearer(UserRole.OPS_ADMIN)).contentType(MediaType.APPLICATION_JSON).content("{\"mark\":\"VERIFIED\"}"))
                .andExpect(status().isBadRequest());
        assertThat(lastPath.get()).isNull();
    }

    @Test
    void resetToDefaultIsADelete() throws Exception {
        replies.put("/profile/drivers/d-1/verification-status", new Reply(200, STATUS_JSON));
        replies.put("/profile/drivers/d-1/verification/items/INSURANCE/review", new Reply(200, REVIEW_JSON));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/drivers/d-1/verification/items/INSURANCE/review")
                        .header("Authorization", bearer(UserRole.OPS_ADMIN)))
                .andExpect(status().isOk());

        assertThat(requests).anyMatch(r -> r.method().equals("DELETE") && r.path().equals("/profile/drivers/d-1/verification/items/INSURANCE/review"));
    }

    @Test
    void approveDriverSendsTheNoteSignedByTheAdmin() throws Exception {
        replies.put("/profile/drivers/d-1/verification/approve", new Reply(200, "{\"success\":true,\"data\":{\"overallStatus\":\"APPROVED\"}}"));

        mockMvc.perform(post("/drivers/d-1/verification/approve").header("Authorization", bearer(UserRole.OPS_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"All clear\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Driver approved"))
                .andExpect(jsonPath("$.data.overallStatus").value("APPROVED"));

        Request sent = requests.stream().filter(r -> r.path().equals("/profile/drivers/d-1/verification/approve")).findFirst().orElseThrow();
        assertThat(sent.method()).isEqualTo("POST");
        assertThat(objectMapper.readTree(sent.body()).get("note").asText()).isEqualTo("All clear");
        assertThat(objectMapper.readTree(sent.body()).get("reviewedBy").asText()).isEqualTo("Ada Ops");

        // No body is fine - the note is optional.
        mockMvc.perform(post("/drivers/d-1/verification/approve").header("Authorization", bearer(UserRole.OPS_ADMIN)))
                .andExpect(status().isOk());
    }

    @Test
    void rejectAndProfileServicesRefusalsComeBackAsIs() throws Exception {
        replies.put("/profile/drivers/d-1/verification/reject", new Reply(400,
                "{\"success\":false,\"message\":\"Flag at least one document, with the reason, to reject\"}"));

        mockMvc.perform(post("/drivers/d-1/verification/reject").header("Authorization", bearer(UserRole.OPS_ADMIN)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Flag at least one document, with the reason, to reject"));
    }

    @Test
    void theLicenceScreenGetsWhatSmileIdReadAndItsImages() throws Exception {
        replies.put("/profile/drivers/d-1/verification/licence", new Reply(200, "{\"success\":true,\"data\":{\"smileJobId\":\"job-9\","
                + "\"licence\":{\"fullName\":\"EMEKA OKAFOR\",\"licenceNumber\":\"LAG12345678\"},"
                + "\"images\":[{\"kind\":\"LICENCE_FRONT\",\"name\":\"id_card_image\",\"url\":\"https://s3/front.jpg\"}]}}"));

        mockMvc.perform(get("/drivers/d-1/verification/licence").header("Authorization", bearer(UserRole.OPS_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.smileJobId").value("job-9"))
                .andExpect(jsonPath("$.data.licence.licenceNumber").value("LAG12345678"))
                .andExpect(jsonPath("$.data.images[0].kind").value("LICENCE_FRONT"));
    }

    @Test
    void theReviewSummarySaysWhenTheDriverFirstSubmitted() throws Exception {
        replies.put("/profile/drivers/d-1/verification-status", new Reply(200, STATUS_JSON.replace(
                "\"documents\":[{\"type\":\"INSURANCE\",\"status\":\"IN_REVIEW\",\"expiresOn\":\"2026-12-09\",",
                "\"documents\":[{\"type\":\"INSURANCE\",\"status\":\"IN_REVIEW\",\"expiresOn\":\"2026-12-09\",\"submittedAt\":\"2026-10-01T07:00:00Z\",")
                .replace("\"documentUrl\":\"https://res.cloudinary.com/c/raw/upload/v1/insurance.pdf\",\"submittedAt\":\"2026-10-03T08:00:00Z\",",
                        "\"documentUrl\":\"https://res.cloudinary.com/c/raw/upload/v1/insurance.pdf\",")));

        mockMvc.perform(get("/drivers/d-1/verification").header("Authorization", bearer(UserRole.OPS_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.submittedAt").value("2026-10-01T07:00:00Z"));
    }

    @Test
    void theAuditLogIsPassedThrough() throws Exception {
        replies.put("/profile/drivers/d-1/verification/history", new Reply(200, "{\"success\":true,\"data\":[{\"item\":\"VEHICLE\","
                + "\"label\":\"Vehicle registration\",\"status\":\"REJECTED\",\"note\":\"Blurry document\",\"decidedBy\":\"Ada Ops\","
                + "\"decidedAt\":\"2026-10-03T09:00:00Z\"}]}"));

        mockMvc.perform(get("/drivers/d-1/verification/history").header("Authorization", bearer(UserRole.OPS_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("REJECTED"))
                .andExpect(jsonPath("$.data[0].note").value("Blurry document"))
                .andExpect(jsonPath("$.data[0].decidedBy").value("Ada Ops"));
        mockMvc.perform(get("/drivers/d-1/verification/history").header("Authorization", bearer(UserRole.DRIVER)))
                .andExpect(status().isForbidden());
    }

    // ---- trips ------------------------------------------------------------------------------------

    private static final String ROUTE_ID = "2f2f5329-2468-4941-be33-e3a81dde7dee";
    private static final String TRIP_ROW = "{\"routePostId\":\"" + ROUTE_ID + "\",\"driverId\":\"d-uuid-1\",\"vehicleId\":\"v-1\","
            + "\"originLabel\":\"Ikorodu\",\"destinationLabel\":\"Victoria Island\",\"departureTime\":\"2026-10-03T15:00:00Z\","
            + "\"status\":\"IN_PROGRESS\",\"seatCapacity\":4,\"seatsAvailable\":1,\"seatsBooked\":3,\"farePerSeat\":1200.00,"
            + "\"totalFare\":3600.00,\"driverRating\":4.88,\"driverRatingCount\":25}";

    private void stubPeople() {
        replies.put("/profile/internal/drivers/batch", new Reply(200,
                "{\"data\":[{\"userId\":\"d-uuid-1\",\"firstName\":\"Emeka\",\"lastName\":\"Okafor\",\"photoUrl\":\"https://img/e.jpg\"}]}"));
        replies.put("/profile/internal/vehicles/batch", new Reply(200,
                "{\"data\":[{\"id\":\"v-1\",\"make\":\"Toyota\",\"model\":\"Camry\",\"year\":2019,\"plateNumber\":\"LND-441-AA\"}]}"));
        replies.put("/identity/internal/users/contacts", new Reply(200,
                "{\"data\":[{\"userId\":\"d-uuid-1\",\"fullName\":\"Emeka Okafor\",\"phone\":\"08053636321\"}]}"));
    }

    @Test
    void tripsTableJoinsTripDriverVehicleAndPhone() throws Exception {
        replies.put("/trip/internal/ops/trips", new Reply(200, "{\"data\":{\"content\":[" + TRIP_ROW + "],"
                + "\"page\":0,\"size\":20,\"totalElements\":35,\"totalPages\":2,\"last\":false}}"));
        stubPeople();

        mockMvc.perform(get("/trips").param("status", "ACTIVE").param("search", "ikorodu").header("Authorization", bearer(UserRole.OPS_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(35))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.content[0].tripRef").value("T-2F2F53"))
                .andExpect(jsonPath("$.data.content[0].status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data.content[0].group").value("ACTIVE"))
                .andExpect(jsonPath("$.data.content[0].driver.ref").value("D-DUUID1"))
                .andExpect(jsonPath("$.data.content[0].driver.name").value("Emeka Okafor"))
                .andExpect(jsonPath("$.data.content[0].driver.phone").value("08053636321"))
                .andExpect(jsonPath("$.data.content[0].vehicle.year").value(2019))
                .andExpect(jsonPath("$.data.content[0].vehicle.plateNumber").value("LND-441-AA"))
                .andExpect(jsonPath("$.data.content[0].totalFare").value(3600.0))
                .andExpect(jsonPath("$.data.content[0].driverRating").value(4.88));
    }

    @Test
    void tabCountsComeFromTheirOwnEndpoint() throws Exception {
        replies.put("/trip/internal/ops/trips/counts", new Reply(200, "{\"data\":{\"all\":127,\"active\":35,\"completed\":82,\"cancelled\":12}}"));

        mockMvc.perform(get("/trips/counts").header("Authorization", bearer(UserRole.OPS_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.all").value(127))
                .andExpect(jsonPath("$.data.cancelled").value(12));
    }

    @Test
    void unknownStatusIsA400NamingTheChoices() throws Exception {
        mockMvc.perform(get("/trips").param("status", "OPEN").header("Authorization", bearer(UserRole.OPS_ADMIN)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid status 'OPEN' - must be one of [ACTIVE, COMPLETED, CANCELLED]"));
        assertThat(paths).as("never reaches trip-service").isEmpty();
    }

    @Test
    void aProfileOutageBlanksDetailsButStillShowsTheTrips() throws Exception {
        replies.put("/trip/internal/ops/trips", new Reply(200, "{\"data\":{\"content\":[" + TRIP_ROW + "],"
                + "\"page\":0,\"size\":20,\"totalElements\":1,\"totalPages\":1,\"last\":true}}"));
        replies.put("/profile/internal/drivers/batch", new Reply(500, "{}"));
        replies.put("/profile/internal/vehicles/batch", new Reply(500, "{}"));
        replies.put("/identity/internal/users/contacts", new Reply(200,
                "{\"data\":[{\"userId\":\"d-uuid-1\",\"fullName\":\"Emeka Okafor\",\"phone\":\"08053636321\"}]}"));

        mockMvc.perform(get("/trips").header("Authorization", bearer(UserRole.OPS_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].routePostId").value(ROUTE_ID))
                .andExpect(jsonPath("$.data.content[0].driver.name").value("Emeka Okafor"))
                .andExpect(jsonPath("$.data.content[0].vehicle").doesNotExist());
    }

    @Test
    void tripServiceDownIsA502() throws Exception {
        replies.put("/trip/internal/ops/trips", new Reply(503, "{\"message\":\"busy\"}"));

        mockMvc.perform(get("/trips").header("Authorization", bearer(UserRole.OPS_ADMIN)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("busy"));
    }

    @Test
    void liveMonitorCountsActiveAndBoarding() throws Exception {
        replies.put("/trip/internal/ops/trips/live", new Reply(200, "{\"data\":["
                + "{\"routePostId\":\"" + ROUTE_ID + "\",\"driverId\":\"d-uuid-1\",\"originLabel\":\"Lekki Phase 1\",\"destinationLabel\":\"Victoria Island\","
                + "\"phase\":\"ACTIVE\",\"seatCapacity\":4,\"seatsBooked\":4,\"estimatedDurationMinutes\":40,\"elapsedMinutes\":18,"
                + "\"etaAt\":\"2026-10-03T07:56:00Z\",\"progress\":0.45,\"location\":{\"available\":true,\"lat\":6.44,\"lng\":3.47}},"
                + "{\"routePostId\":\"aaaa1111-0000\",\"driverId\":\"d-uuid-1\",\"originLabel\":\"Surulere\",\"destinationLabel\":\"Lagos Island\","
                + "\"phase\":\"BOARDING\",\"seatCapacity\":4,\"seatsBooked\":1,\"estimatedDurationMinutes\":30,\"elapsedMinutes\":0,\"progress\":0.0}]}"));
        stubPeople();

        mockMvc.perform(get("/trips/live").header("Authorization", bearer(UserRole.OPS_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.active").value(1))
                .andExpect(jsonPath("$.data.boarding").value(1))
                .andExpect(jsonPath("$.data.trips[0].driver.name").value("Emeka Okafor"))
                .andExpect(jsonPath("$.data.trips[0].location.lat").value(6.44))
                .andExpect(jsonPath("$.data.trips[1].location.available").value(false));
    }

    @Test
    void tripDetailNamesEveryPassenger() throws Exception {
        replies.put("/trip/internal/ops/trips/" + ROUTE_ID, new Reply(200, "{\"data\":{\"trip\":" + TRIP_ROW + ","
                + "\"passengers\":[{\"bookingId\":\"b-1\",\"riderId\":\"r-1\",\"seatCount\":1,\"fareTotal\":1200.00,\"status\":\"IN_PROGRESS\"}]}}"));
        stubPeople();
        replies.put("/profile/internal/riders/batch", new Reply(200,
                "{\"data\":[{\"userId\":\"r-1\",\"firstName\":\"Amaka\",\"lastName\":\"Obi\"}]}"));
        replies.put("/identity/internal/users/contacts", new Reply(200,
                "{\"data\":[{\"userId\":\"d-uuid-1\",\"phone\":\"08053636321\"},{\"userId\":\"r-1\",\"phone\":\"08011112222\"}]}"));

        mockMvc.perform(get("/trips/" + ROUTE_ID).header("Authorization", bearer(UserRole.OPS_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.trip.tripRef").value("T-2F2F53"))
                .andExpect(jsonPath("$.data.passengers[0].name").value("Amaka Obi"))
                .andExpect(jsonPath("$.data.passengers[0].phone").value("08011112222"));
    }

    private String bearer(UserRole role) {
        return "Bearer " + jwtUtil.generateToken("admin-1", "08000000000", "Ada", "Ops", role);
    }

    private static HttpServer startFakeProfileService() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", OpsServiceIntegrationTest::handle);
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
        paths.add(exchange.getRequestURI().toString());
        requests.add(new Request(lastMethod.get(), lastPath.get(), lastBody.get()));
        Reply reply = replies.getOrDefault(exchange.getRequestURI().getPath(), new Reply(nextStatus.get(), nextBody.get()));
        byte[] response = reply.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(reply.status(), response.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(response);
        }
    }
}
