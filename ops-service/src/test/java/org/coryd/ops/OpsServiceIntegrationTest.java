package org.coryd.ops;

import org.coryd.ops.controller.UserController;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
        // The revocation feed is driven by hand here (revokedTokens...), so nothing calls the fake services in the background.
        "coryd.token-revocation.kafka.enabled=false",
        "coryd.token-revocation.bootstrap.enabled=false",
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
        registry.add("services.payment.url", () -> base + "/payment");
    }

    @Autowired MockMvc mockMvc;
    @Autowired JwtUtil jwtUtil;
    @Autowired ObjectMapper objectMapper;
    @Autowired org.coryd.common.security.revocation.TokenRevocationRegistry revocations;
    @Autowired org.coryd.common.security.revocation.TokenRevocationBootstrap revocationBootstrap;

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
        revocations.clear();
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
    void saveChangesGoesOutSignedByTheAdminAndReturnsTheScreen() throws Exception {
        replies.put("/profile/drivers/d-1/verification/save", new Reply(200, "{\"success\":true,\"data\":{\"overallStatus\":\"REJECTED\"}}"));
        replies.put("/profile/drivers/d-1/verification/review", new Reply(200, REVIEW_JSON.replace("\"canReject\":true", "\"canReject\":true,\"canSave\":true")));

        mockMvc.perform(post("/drivers/d-1/verification/save").header("Authorization", bearer(UserRole.OPS_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Changes saved"))
                .andExpect(jsonPath("$.data.overallStatus").value("REJECTED"))
                .andExpect(jsonPath("$.data.review.canSave").value(true));

        Request sent = requests.stream().filter(r -> r.path().equals("/profile/drivers/d-1/verification/save")).findFirst().orElseThrow();
        assertThat(objectMapper.readTree(sent.body()).get("reviewedBy").asText()).isEqualTo("Ada Ops");

        replies.put("/profile/drivers/d-1/verification/save", new Reply(400,
                "{\"success\":false,\"message\":\"Nothing to save - verify or flag a document first\"}"));
        mockMvc.perform(post("/drivers/d-1/verification/save").header("Authorization", bearer(UserRole.OPS_ADMIN)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Nothing to save - verify or flag a document first"));
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

    // ---- drivers directory ------------------------------------------------------------------------

    private static final String INDEXED_EMEKA = "{\"driverId\":\"aaaa1111-1\",\"firstName\":\"Emeka\",\"lastName\":\"Okafor\","
            + "\"phone\":\"08053636321\",\"joinedAt\":\"2026-01-12T09:00:00Z\",\"status\":\"VERIFIED\",\"verificationStatus\":\"APPROVED\","
            + "\"vehicleMake\":\"Toyota\",\"vehicleModel\":\"Camry\",\"vehicleYear\":2019,\"vehicleColor\":\"Black\",\"plateNumber\":\"LND-441-AA\","
            + "\"trips\":213,\"ratingAvg\":4.88,\"ratingCount\":40,\"reliabilityPercent\":91,\"reportsFiled\":0,"
            + "\"earningsNet\":1847200.00,\"earningsGross\":1944421.05,\"lastTripAt\":\"2026-11-12T07:30:00Z\"}";
    private static final String INDEXED_KINGSLEY = "{\"driverId\":\"cccc3333-3\",\"firstName\":\"Kingsley\",\"lastName\":\"Nwosu\","
            + "\"joinedAt\":\"2026-05-01T09:00:00Z\",\"status\":\"PENDING\",\"verificationStatus\":\"PENDING\"}";

    @Test
    void theDirectoryIsFilteredSortedAndPagedByProfileServiceAndShapedHere() throws Exception {
        replies.put("/profile/internal/drivers/index", new Reply(200, "{\"data\":{\"content\":[" + INDEXED_KINGSLEY + "," + INDEXED_EMEKA
                + "],\"page\":1,\"size\":2,\"totalElements\":6,\"totalPages\":3,\"last\":false}}"));

        mockMvc.perform(get("/drivers?status=VERIFIED&search=oka&sort=EARNINGS&direction=DESC&page=1&size=2")
                        .header("Authorization", bearer(UserRole.OPS_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(6))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.content[0].driver.name").value("Kingsley Nwosu"))
                .andExpect(jsonPath("$.data.content[0].vehicle").doesNotExist())
                .andExpect(jsonPath("$.data.content[0].earnings").doesNotExist())
                .andExpect(jsonPath("$.data.content[1].driver.ref").value("D-AAAA11"))
                .andExpect(jsonPath("$.data.content[1].driver.phone").value("08053636321"))
                .andExpect(jsonPath("$.data.content[1].vehicle.plateNumber").value("LND-441-AA"))
                .andExpect(jsonPath("$.data.content[1].trips").value(213))
                .andExpect(jsonPath("$.data.content[1].rating").value(4.88))
                .andExpect(jsonPath("$.data.content[1].earnings").value(1847200.00))
                .andExpect(jsonPath("$.data.content[1].reliabilityPercent").value(91))
                .andExpect(jsonPath("$.data.content[1].status").value("VERIFIED"))
                .andExpect(jsonPath("$.data.content[1].verificationStatus").value("APPROVED"))
                .andExpect(jsonPath("$.data.content[1].lastTripAt").value("2026-11-12T07:30:00Z"))
                .andExpect(jsonPath("$.data.content[0].lastTripAt").doesNotExist());

        assertThat(paths).anyMatch(p -> p.startsWith("/profile/internal/drivers/index?") && p.contains("status=VERIFIED")
                && p.contains("search=oka") && p.contains("sort=EARNINGS") && p.contains("direction=DESC") && p.contains("page=1") && p.contains("size=2"));
    }

    @Test
    void driversCanBeSortedByLastTripAndTheCountsCarryTheActiveOnes() throws Exception {
        replies.put("/profile/internal/drivers/index", new Reply(200, "{\"data\":{\"content\":[" + INDEXED_EMEKA
                + "],\"page\":0,\"size\":20,\"totalElements\":1,\"totalPages\":1,\"last\":true}}"));
        replies.put("/profile/internal/drivers/index/counts", new Reply(200,
                "{\"data\":{\"all\":21,\"verified\":12,\"pending\":5,\"resubmitted\":2,\"suspended\":2,\"activeLast7Days\":9}}"));
        String token = bearer(UserRole.OPS_ADMIN);

        mockMvc.perform(get("/drivers?sort=LAST_TRIP&direction=DESC").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].lastTripAt").value("2026-11-12T07:30:00Z"));
        assertThat(paths).anyMatch(p -> p.startsWith("/profile/internal/drivers/index?") && p.contains("sort=LAST_TRIP"));
        mockMvc.perform(get("/drivers/counts").header("Authorization", token))
                .andExpect(jsonPath("$.data.all").value(21))
                .andExpect(jsonPath("$.data.activeLast7Days").value(9));
    }

    @Test
    void pageSizeIsCappedAndBadFiltersAreRefusedBeforeReachingProfileService() throws Exception {
        replies.put("/profile/internal/drivers/index", new Reply(200, "{\"data\":{\"content\":[],\"page\":0,\"size\":50,"
                + "\"totalElements\":0,\"totalPages\":0,\"last\":true}}"));
        String token = bearer(UserRole.OPS_ADMIN);

        mockMvc.perform(get("/drivers?size=500").header("Authorization", token)).andExpect(status().isOk());
        assertThat(paths).anyMatch(p -> p.startsWith("/profile/internal/drivers/index?") && p.contains("size=50"));
        paths.clear();
        mockMvc.perform(get("/drivers?status=GONE").header("Authorization", token)).andExpect(status().isBadRequest());
        mockMvc.perform(get("/drivers?sort=COLOUR").header("Authorization", token)).andExpect(status().isBadRequest());
        assertThat(paths).isEmpty();
    }

    @Test
    void tabCountsFollowTheSearch() throws Exception {
        replies.put("/profile/internal/drivers/index/counts", new Reply(200,
                "{\"data\":{\"all\":21,\"verified\":12,\"pending\":5,\"resubmitted\":2,\"suspended\":2}}"));

        mockMvc.perform(get("/drivers/counts?search=okafor").header("Authorization", bearer(UserRole.OPS_ADMIN)))
                .andExpect(jsonPath("$.data.all").value(21))
                .andExpect(jsonPath("$.data.verified").value(12))
                .andExpect(jsonPath("$.data.pending").value(5))
                .andExpect(jsonPath("$.data.resubmitted").value(2))
                .andExpect(jsonPath("$.data.suspended").value(2));
        assertThat(paths).anyMatch(p -> p.startsWith("/profile/internal/drivers/index/counts?") && p.contains("search=okafor"));
    }

    @Test
    void aDriversProfileHasTheirCarPerformanceAccountAndSuspension() throws Exception {
        replies.put("/profile/internal/drivers/index/dddd4444-4", new Reply(200, "{\"data\":{\"driverId\":\"dddd4444-4\","
                + "\"firstName\":\"Aisha\",\"lastName\":\"Lawal\",\"phone\":\"08055556666\",\"joinedAt\":\"2026-02-01T09:00:00Z\","
                + "\"status\":\"SUSPENDED\",\"verificationStatus\":\"APPROVED\",\"suspendedAt\":\"2026-10-01T10:00:00Z\","
                + "\"suspensionReason\":\"Threatened a rider\",\"vehicleMake\":\"Kia\",\"vehicleModel\":\"Rio\",\"vehicleYear\":2020,"
                + "\"plateNumber\":\"EKY-9-ZZ\",\"trips\":321,\"ratingAvg\":4.9,\"ratingCount\":50,\"reliabilityPercent\":97,"
                + "\"reportsFiled\":1,\"earningsNet\":190000.00,\"earningsGross\":200000.00}}"));

        mockMvc.perform(get("/drivers/dddd4444-4").header("Authorization", bearer(UserRole.OPS_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.driver.ref").value("D-DDDD44"))
                .andExpect(jsonPath("$.data.driver.phone").value("08055556666"))
                .andExpect(jsonPath("$.data.status").value("SUSPENDED"))
                .andExpect(jsonPath("$.data.verificationStatus").value("APPROVED"))
                .andExpect(jsonPath("$.data.suspension.reason").value("Threatened a rider"))
                .andExpect(jsonPath("$.data.vehicle.model").value("Rio"))
                .andExpect(jsonPath("$.data.performance.totalTrips").value(321))
                .andExpect(jsonPath("$.data.performance.rating").value(4.9))
                .andExpect(jsonPath("$.data.performance.ratingCount").value(50))
                .andExpect(jsonPath("$.data.performance.totalEarnings").value(190000.00))
                .andExpect(jsonPath("$.data.performance.reliabilityPercent").value(97))
                .andExpect(jsonPath("$.data.performance.reportsFiled").value(1))
                .andExpect(jsonPath("$.data.account.ref").value("D-DDDD44"))
                .andExpect(jsonPath("$.data.account.joinedAt").value("2026-02-01T09:00:00Z"));
    }

    @Test
    void anUnknownDriverIsA404AndTheVerificationQueueStillAnswersAtItsOwnPath() throws Exception {
        replies.put("/profile/internal/drivers/index/nobody", new Reply(404, "{\"success\":false,\"message\":\"Driver not found\"}"));
        mockMvc.perform(get("/drivers/nobody").header("Authorization", bearer(UserRole.OPS_ADMIN)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Driver not found"));

        replies.put("/profile/drivers/verification/queue/counts",
                new Reply(200, "{\"data\":{\"all\":1,\"pending\":1,\"inReview\":0,\"resubmitted\":0}}"));
        mockMvc.perform(get("/drivers/verification/queue/counts").header("Authorization", bearer(UserRole.OPS_ADMIN)))
                .andExpect(jsonPath("$.data.pending").value(1));
        mockMvc.perform(get("/drivers").header("Authorization", bearer(UserRole.RIDER))).andExpect(status().isForbidden());
    }

    @Test
    void whenProfileServiceIsDownTheDirectorySaysSo() throws Exception {
        replies.put("/profile/internal/drivers/index", new Reply(503, "{\"success\":false,\"message\":\"Unavailable\"}"));
        mockMvc.perform(get("/drivers").header("Authorization", bearer(UserRole.OPS_ADMIN)))
                .andExpect(status().isServiceUnavailable());
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

    // ---- Document Expiry Tracker -----------------------------------------------------------------

    private static final String EXPIRY_ROW = "{\"documentId\":\"doc-1\",\"driverId\":\"d-uuid-1\",\"firstName\":\"Emeka\",\"lastName\":\"Okafor\","
            + "\"phone\":\"08053636321\",\"documentType\":\"ROADWORTHINESS_CERT\",\"expiresOn\":\"2026-09-02\",\"daysLeft\":-3,"
            + "\"status\":\"EXPIRED\",\"verificationStatus\":\"EXPIRED\",\"lastNotifiedAt\":null,\"notifiable\":true,\"notifiedRecently\":false}";

    @Test
    void documentExpiryTableComesFromProfileServiceWithTheDriversRef() throws Exception {
        replies.put("/profile/internal/document-expiry", new Reply(200, "{\"data\":{\"content\":[" + EXPIRY_ROW + "],"
                + "\"page\":0,\"size\":10,\"totalElements\":14,\"totalPages\":2,\"last\":false}}"));

        mockMvc.perform(get("/document-expiry").param("tab", "EXPIRED").param("search", "emeka").param("sort", "DAYS_LEFT")
                        .param("direction", "DESC").param("size", "10").header("Authorization", bearer(UserRole.OPS_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(14))
                .andExpect(jsonPath("$.data.content[0].documentId").value("doc-1"))
                .andExpect(jsonPath("$.data.content[0].driver.ref").value("D-DUUID1"))
                .andExpect(jsonPath("$.data.content[0].driver.name").value("Emeka Okafor"))
                .andExpect(jsonPath("$.data.content[0].driver.phone").value("08053636321"))
                .andExpect(jsonPath("$.data.content[0].documentLabel").value("Roadworthiness Certificate"))
                .andExpect(jsonPath("$.data.content[0].daysLeft").value(-3))
                .andExpect(jsonPath("$.data.content[0].status").value("EXPIRED"))
                .andExpect(jsonPath("$.data.content[0].canNotify").value(true));
        assertThat(paths.get(0)).contains("tab=EXPIRED").contains("search=emeka").contains("sort=DAYS_LEFT").contains("direction=DESC").contains("size=10");
    }

    @Test
    void documentExpiryCardsAndBadgesComeFromTheirOwnEndpoint() throws Exception {
        replies.put("/profile/internal/document-expiry/counts", new Reply(200,
                "{\"data\":{\"all\":14,\"expiringSoon\":9,\"expired\":5,\"verified\":0,\"notVerified\":0,\"withoutExpiryDate\":2}}"));

        mockMvc.perform(get("/document-expiry/counts").header("Authorization", bearer(UserRole.OPS_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.all").value(14))
                .andExpect(jsonPath("$.data.expiringSoon").value(9))
                .andExpect(jsonPath("$.data.expired").value(5))
                .andExpect(jsonPath("$.data.withoutExpiryDate").value(2));
    }

    @Test
    void notifyIsSignedWithTheAdminsNameAndReturnsTheRow() throws Exception {
        replies.put("/profile/internal/document-expiry/doc-1/notify", new Reply(200, "{\"data\":"
                + EXPIRY_ROW.replace("\"lastNotifiedAt\":null", "\"lastNotifiedAt\":\"2026-11-01T09:00:00Z\",\"lastNotifiedBy\":\"Ada Ops\"")
                        .replace("\"notifiedRecently\":false", "\"notifiedRecently\":true") + "}"));

        mockMvc.perform(post("/document-expiry/doc-1/notify").header("Authorization", bearer(UserRole.OPS_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lastNotifiedBy").value("Ada Ops"))
                .andExpect(jsonPath("$.data.notifiedRecently").value(true))
                .andExpect(jsonPath("$.data.canNotify").value(false));
        assertThat(lastMethod.get()).isEqualTo("POST");
        assertThat(paths.get(0)).contains("by=Ada%20Ops");
    }

    @Test
    void notifyingTooSoonPassesProfileServicesReasonThrough() throws Exception {
        replies.put("/profile/internal/document-expiry/doc-1/notify", new Reply(400,
                "{\"success\":false,\"message\":\"The driver was reminded at 1 Nov, 10:00 AM - you can remind them again after 2 Nov, 10:00 AM\"}"));

        mockMvc.perform(post("/document-expiry/doc-1/notify").header("Authorization", bearer(UserRole.OPS_ADMIN)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("The driver was reminded at 1 Nov, 10:00 AM - you can remind them again after 2 Nov, 10:00 AM"));
    }

    @Test
    void notifyAllFollowsTheTabAndSearch() throws Exception {
        replies.put("/profile/internal/document-expiry/notify-all", new Reply(200, "{\"data\":{\"notified\":8,\"skippedRecentlyNotified\":1}}"));

        mockMvc.perform(post("/document-expiry/notify-all").param("tab", "EXPIRING_SOON").param("search", "fatima")
                        .header("Authorization", bearer(UserRole.OPS_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.notified").value(8))
                .andExpect(jsonPath("$.data.skippedRecentlyNotified").value(1));
        assertThat(paths.get(0)).contains("tab=EXPIRING_SOON").contains("search=fatima").contains("by=Ada%20Ops");
    }

    @Test
    void documentExpiryIsForOpsAdminsOnly() throws Exception {
        mockMvc.perform(get("/document-expiry")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/document-expiry/notify-all").header("Authorization", bearer(UserRole.DRIVER))).andExpect(status().isForbidden());
        mockMvc.perform(get("/document-expiry").param("tab", "SOON").header("Authorization", bearer(UserRole.OPS_ADMIN)))
                .andExpect(status().isBadRequest());
        assertThat(paths).isEmpty();
    }

    // ---- Riders Directory ------------------------------------------------------------------------

    private static final String RIDER_ROW = "{\"riderId\":\"r-uuid-1\",\"firstName\":\"Emeka\",\"lastName\":\"Okafor\",\"phone\":\"08053636321\","
            + "\"joinedAt\":\"2026-01-12T09:00:00Z\",\"status\":\"ACTIVE\",\"trips\":213,\"ratingAvg\":4.88,\"ratingCount\":40,"
            + "\"reliabilityPercent\":91,\"reportsFiled\":0,\"totalSpent\":157000.00,"
            + "\"identityStatus\":\"APPROVED\",\"lastTripAt\":\"2026-11-10T18:05:00Z\"}";

    @Test
    void ridersTableComesFromTheRiderIndex() throws Exception {
        replies.put("/profile/internal/riders/index", new Reply(200, "{\"data\":{\"content\":[" + RIDER_ROW + "],"
                + "\"page\":0,\"size\":10,\"totalElements\":62,\"totalPages\":7,\"last\":false}}"));

        mockMvc.perform(get("/riders").param("status", "ACTIVE").param("search", "emeka").param("sort", "RETENTION").param("direction", "DESC")
                        .param("size", "10").header("Authorization", bearer(UserRole.OPS_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(62))
                .andExpect(jsonPath("$.data.content[0].rider.ref").value("R-RUUID1"))
                .andExpect(jsonPath("$.data.content[0].rider.name").value("Emeka Okafor"))
                .andExpect(jsonPath("$.data.content[0].trips").value(213))
                .andExpect(jsonPath("$.data.content[0].reliabilityPercent").value(91))
                .andExpect(jsonPath("$.data.content[0].rating").value(4.88))
                .andExpect(jsonPath("$.data.content[0].totalSpent").value(157000.0))
                .andExpect(jsonPath("$.data.content[0].reportsFiled").value(0))
                .andExpect(jsonPath("$.data.content[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.content[0].identityStatus").value("APPROVED"))
                .andExpect(jsonPath("$.data.content[0].lastTripAt").value("2026-11-10T18:05:00Z"));
        assertThat(paths.get(0)).contains("status=ACTIVE").contains("search=emeka").contains("sort=RETENTION").contains("direction=DESC");
        assertThat(paths.get(0)).doesNotContain("identity=");
    }

    @Test
    void ridersCanBeFilteredByIdentityAndSortedByLastTrip() throws Exception {
        replies.put("/profile/internal/riders/index", new Reply(200, "{\"data\":{\"content\":[" + RIDER_ROW + "],"
                + "\"page\":0,\"size\":20,\"totalElements\":1,\"totalPages\":1,\"last\":true}}"));
        String token = bearer(UserRole.OPS_ADMIN);

        mockMvc.perform(get("/riders").param("identity", "APPROVED").param("sort", "LAST_TRIP").param("direction", "DESC")
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].identityStatus").value("APPROVED"));
        assertThat(paths.get(0)).contains("identity=APPROVED").contains("sort=LAST_TRIP").contains("direction=DESC");

        paths.clear();
        mockMvc.perform(get("/riders").param("identity", "MAYBE").header("Authorization", token)).andExpect(status().isBadRequest());
        assertThat(paths).isEmpty();
    }

    @Test
    void riderTabBadgesComeFromTheirOwnEndpoint() throws Exception {
        replies.put("/profile/internal/riders/index/counts", new Reply(200,
                "{\"data\":{\"all\":62,\"active\":52,\"suspended\":10,\"verified\":40,\"activeLast7Days\":31}}"));

        mockMvc.perform(get("/riders/counts").header("Authorization", bearer(UserRole.OPS_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.all").value(62))
                .andExpect(jsonPath("$.data.suspended").value(10))
                .andExpect(jsonPath("$.data.verified").value(40))
                .andExpect(jsonPath("$.data.activeLast7Days").value(31));
    }

    @Test
    void aRidersProfileHasAccountActivityAndAnySuspension() throws Exception {
        replies.put("/profile/internal/riders/index/r-uuid-1", new Reply(200, "{\"data\":" + RIDER_ROW
                .replace("\"status\":\"ACTIVE\"", "\"status\":\"SUSPENDED\",\"suspendedAt\":\"2026-10-01T10:00:00Z\",\"suspensionReason\":\"Abusive\"") + "}"));

        mockMvc.perform(get("/riders/r-uuid-1").header("Authorization", bearer(UserRole.OPS_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rider.ref").value("R-RUUID1"))
                .andExpect(jsonPath("$.data.status").value("SUSPENDED"))
                .andExpect(jsonPath("$.data.suspension.reason").value("Abusive"))
                .andExpect(jsonPath("$.data.account.joinedAt").value("2026-01-12T09:00:00Z"))
                .andExpect(jsonPath("$.data.activity.totalTrips").value(213))
                .andExpect(jsonPath("$.data.activity.totalSpent").value(157000.0))
                .andExpect(jsonPath("$.data.activity.reliabilityPercent").value(91))
                .andExpect(jsonPath("$.data.activity.reportsFiled").value(0));
    }

    @Test
    void anUnknownRiderIsA404() throws Exception {
        replies.put("/profile/internal/riders/index/nope", new Reply(404, "{\"success\":false,\"message\":\"Rider not found\"}"));

        mockMvc.perform(get("/riders/nope").header("Authorization", bearer(UserRole.OPS_ADMIN)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Rider not found"));
        mockMvc.perform(get("/riders/nope/trips").header("Authorization", bearer(UserRole.OPS_ADMIN)))
                .andExpect(status().isNotFound());
    }

    private static final String RIDER_TRIP = "{\"bookingId\":\"b-uuid-1\",\"routePostId\":\"" + ROUTE_ID + "\",\"status\":\"COMPLETED\","
            + "\"originLabel\":\"Ikorodu\",\"destinationLabel\":\"Victoria Island\",\"departureTime\":\"2026-07-11T06:00:00Z\","
            + "\"seatCount\":1,\"fareTotal\":1800.00,\"driverId\":\"d-uuid-1\",\"driverName\":\"Emeka Okafor\","
            + "\"driverRatingAvg\":4.9,\"driverRatingCount\":178}";

    @Test
    void tripHistoryAddsWhatWasPaidAndRefunded() throws Exception {
        replies.put("/profile/internal/riders/index/r-uuid-1", new Reply(200, "{\"data\":" + RIDER_ROW + "}"));
        replies.put("/trip/internal/ops/riders/r-uuid-1/trips", new Reply(200, "{\"data\":{\"content\":[" + RIDER_TRIP + "],"
                + "\"page\":0,\"size\":10,\"totalElements\":1,\"totalPages\":1,\"last\":true}}"));
        replies.put("/payment/internal/ops/payments/bookings", new Reply(200, "{\"data\":[{\"bookingId\":\"b-uuid-1\",\"status\":\"PAID\","
                + "\"method\":\"PAYSTACK\",\"cardLast4\":\"4081\",\"amountPaid\":1800.00,\"refunded\":0,\"refundPending\":0,\"netPaid\":1800.00}]}"));

        mockMvc.perform(get("/riders/r-uuid-1/trips").param("search", "wed").header("Authorization", bearer(UserRole.OPS_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].ref").value("B-BUUID1"))
                .andExpect(jsonPath("$.data.content[0].originLabel").value("Ikorodu"))
                .andExpect(jsonPath("$.data.content[0].fareTotal").value(1800.0))
                .andExpect(jsonPath("$.data.content[0].driver.name").value("Emeka Okafor"))
                .andExpect(jsonPath("$.data.content[0].driver.rating").value(4.9))
                .andExpect(jsonPath("$.data.content[0].payment.status").value("PAID"))
                .andExpect(jsonPath("$.data.content[0].payment.netPaid").value(1800.0));
        assertThat(paths).anyMatch(p -> p.startsWith("/trip/internal/ops/riders/r-uuid-1/trips") && p.contains("search=wed"));
    }

    @Test
    void tripHistoryStillShowsWhenPaymentServiceIsDown() throws Exception {
        replies.put("/profile/internal/riders/index/r-uuid-1", new Reply(200, "{\"data\":" + RIDER_ROW + "}"));
        replies.put("/trip/internal/ops/riders/r-uuid-1/trips", new Reply(200, "{\"data\":{\"content\":[" + RIDER_TRIP + "],"
                + "\"page\":0,\"size\":10,\"totalElements\":1,\"totalPages\":1,\"last\":true}}"));
        replies.put("/payment/internal/ops/payments/bookings", new Reply(500, "{}"));

        mockMvc.perform(get("/riders/r-uuid-1/trips").header("Authorization", bearer(UserRole.OPS_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].bookingId").value("b-uuid-1"))
                .andExpect(jsonPath("$.data.content[0].payment").doesNotExist());
    }

    @Test
    void aReceiptHasTheRiderRouteDriverFareAndPayment() throws Exception {
        replies.put("/profile/internal/riders/index/r-uuid-1", new Reply(200, "{\"data\":" + RIDER_ROW + "}"));
        replies.put("/trip/internal/ops/riders/r-uuid-1/trips/b-uuid-1/receipt", new Reply(200, "{\"data\":{\"bookingId\":\"b-uuid-1\","
                + "\"riderId\":\"r-uuid-1\",\"routePostId\":\"" + ROUTE_ID + "\",\"status\":\"CANCELLED_RIDER\",\"originLabel\":\"Ikorodu\","
                + "\"destinationLabel\":\"Victoria Island\",\"pickupLabel\":\"Ikorodu Garage\",\"dropoffLabel\":\"Victoria Island\","
                + "\"departureTime\":\"2026-07-11T06:00:00Z\",\"paidAt\":\"2026-07-10T18:00:00Z\",\"driverId\":\"d-uuid-1\","
                + "\"driverName\":\"Emeka Okafor\",\"vehiclePlate\":\"LND-441-AA\",\"seatCount\":2,\"farePerSeat\":900.00,\"fareTotal\":1800.00}}"));
        replies.put("/payment/internal/ops/payments/bookings", new Reply(200, "{\"data\":[{\"bookingId\":\"b-uuid-1\",\"status\":\"PAID\","
                + "\"amountPaid\":1800.00,\"refunded\":1350.00,\"refundPending\":0,\"netPaid\":450.00}]}"));

        mockMvc.perform(get("/riders/r-uuid-1/trips/b-uuid-1/receipt").header("Authorization", bearer(UserRole.OPS_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ref").value("B-BUUID1"))
                .andExpect(jsonPath("$.data.rider.name").value("Emeka Okafor"))
                .andExpect(jsonPath("$.data.route.pickupLabel").value("Ikorodu Garage"))
                .andExpect(jsonPath("$.data.driver.ref").value("D-DUUID1"))
                .andExpect(jsonPath("$.data.driver.vehiclePlate").value("LND-441-AA"))
                .andExpect(jsonPath("$.data.seatCount").value(2))
                .andExpect(jsonPath("$.data.paidAt").value("2026-07-10T18:00:00Z"))
                .andExpect(jsonPath("$.data.payment.refunded").value(1350.0))
                .andExpect(jsonPath("$.data.payment.netPaid").value(450.0));
    }

    @Test
    void ridersAreForOpsAdminsOnly() throws Exception {
        mockMvc.perform(get("/riders")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/riders").header("Authorization", bearer(UserRole.RIDER))).andExpect(status().isForbidden());
        mockMvc.perform(get("/riders").param("sort", "AGE").header("Authorization", bearer(UserRole.OPS_ADMIN))).andExpect(status().isBadRequest());
        assertThat(paths).isEmpty();
    }

    // ---- identity (NIN) screen ---------------------------------------------------------------------------------

    private static final String PERSON_JSON = "{\"success\":true,\"data\":{\"person\":{\"userId\":\"7c1e22aa-0000\",\"firstName\":\"Bola\","
            + "\"lastName\":\"Ige\",\"rider\":true,\"driver\":false},\"tab\":\"APPROVED\",\"status\":\"APPROVED\",\"providerOverridden\":true,"
            + "\"actions\":{\"canApprove\":false,\"approveBlockedReason\":\"This person's identity is already verified\"},\"history\":[]}}";

    @Test
    void identityListForwardsTabSearchSortAndPagingAndAddsEachPersonsRef() throws Exception {
        replies.put("/profile/identity-verification/people", new Reply(200, "{\"success\":true,\"data\":{\"content\":["
                + "{\"userId\":\"7c1e22aa-0000\",\"firstName\":\"Bola\",\"rider\":true,\"driver\":false,\"tab\":\"FAILED\"},"
                + "{\"userId\":\"d0d0d0d0-1111\",\"firstName\":\"Gbenga\",\"rider\":false,\"driver\":true,\"tab\":\"FAILED\"}],"
                + "\"page\":1,\"size\":10,\"totalElements\":12,\"totalPages\":2,\"last\":true}}"));
        String token = bearer(UserRole.OPS_ADMIN);

        mockMvc.perform(get("/identity-verification/people?tab=FAILED&search=bola&role=RIDER&sort=ATTEMPTS&direction=DESC&page=1&size=10")
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].ref").value("R-7C1E22"))
                .andExpect(jsonPath("$.data.content[1].ref").value("D-D0D0D0"))
                .andExpect(jsonPath("$.data.totalElements").value(12));

        assertThat(paths).anyMatch(p -> p.startsWith("/profile/identity-verification/people?") && p.contains("tab=FAILED")
                && p.contains("search=bola") && p.contains("role=RIDER") && p.contains("sort=ATTEMPTS") && p.contains("direction=DESC")
                && p.contains("page=1") && p.contains("size=10"));
        assertThat(lastAuth.get()).isEqualTo(token);
        mockMvc.perform(get("/identity-verification/people?tab=DONE").header("Authorization", token)).andExpect(status().isBadRequest());
        mockMvc.perform(get("/identity-verification/people?sort=AGE").header("Authorization", token)).andExpect(status().isBadRequest());
    }

    @Test
    void identityCountsDetailHistoryAndImagesArePassedThrough() throws Exception {
        replies.put("/profile/identity-verification/people/counts", new Reply(200,
                "{\"data\":{\"all\":7,\"pending\":1,\"inReview\":1,\"failed\":2,\"approved\":1,\"lockedOut\":2}}"));
        replies.put("/profile/identity-verification/people/7c1e22aa-0000", new Reply(200, PERSON_JSON));
        replies.put("/profile/identity-verification/people/7c1e22aa-0000/history", new Reply(200,
                "{\"data\":[{\"subject\":\"IDENTITY\",\"label\":\"Identity (NIN)\",\"action\":\"OVERRIDE_APPROVED\"}]}"));
        replies.put("/profile/identity-verification/people/7c1e22aa-0000/checks/chk-1/images", new Reply(200,
                "{\"data\":{\"checkId\":\"chk-1\",\"smileJobId\":\"job-9\",\"images\":[{\"kind\":\"SELFIE\",\"url\":\"https://smile/s.jpg\"}]}}"));
        String token = bearer(UserRole.OPS_ADMIN);

        mockMvc.perform(get("/identity-verification/people/counts?search=0803").header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.lockedOut").value(2));
        mockMvc.perform(get("/identity-verification/people/7c1e22aa-0000").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.person.ref").value("R-7C1E22"))
                .andExpect(jsonPath("$.data.providerOverridden").value(true));
        mockMvc.perform(get("/identity-verification/people/7c1e22aa-0000/history").header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].action").value("OVERRIDE_APPROVED"));
        mockMvc.perform(get("/identity-verification/people/7c1e22aa-0000/checks/chk-1/images").header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.images[0].kind").value("SELFIE"));
        assertThat(paths).anyMatch(p -> p.equals("/profile/identity-verification/people/counts?search=0803"));
    }

    @Test
    void identityActionsAreSignedByTheSignedInAdmin() throws Exception {
        replies.put("/profile/identity-verification/people/7c1e22aa-0000/approve", new Reply(200, PERSON_JSON));
        replies.put("/profile/identity-verification/people/7c1e22aa-0000/reject", new Reply(200, PERSON_JSON));
        replies.put("/profile/identity-verification/people/7c1e22aa-0000/reset-attempts", new Reply(200, PERSON_JSON));
        String token = bearer(UserRole.OPS_ADMIN);

        mockMvc.perform(post("/identity-verification/people/7c1e22aa-0000/approve").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"Checked on a video call\",\"reviewedBy\":\"someone-else\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Identity approved"))
                .andExpect(jsonPath("$.data.person.ref").value("R-7C1E22"));
        JsonNode approve = objectMapper.readTree(lastBody.get());
        assertThat(approve.get("note").asText()).isEqualTo("Checked on a video call");
        assertThat(approve.get("reviewedBy").asText()).isEqualTo("Ada Ops");
        assertThat(lastMethod.get()).isEqualTo("POST");

        mockMvc.perform(post("/identity-verification/people/7c1e22aa-0000/reject").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"The NIN is someone else's\"}"))
                .andExpect(status().isOk());
        assertThat(objectMapper.readTree(lastBody.get()).get("reason").asText()).isEqualTo("The NIN is someone else's");

        mockMvc.perform(post("/identity-verification/people/7c1e22aa-0000/reset-attempts").header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("Tries reset"));
        assertThat(paths).contains("/profile/identity-verification/people/7c1e22aa-0000/reset-attempts");
        assertThat(lastPath.get()).as("then added to the account history").isEqualTo("/identity/internal/users/7c1e22aa-0000/account-actions");
    }

    @Test
    void aRejectionWithoutAReasonNeverLeavesTheConsole() throws Exception {
        String token = bearer(UserRole.OPS_ADMIN);
        mockMvc.perform(post("/identity-verification/people/u-1/reject").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("reason: is required - the person sees it"));
        mockMvc.perform(post("/identity-verification/people/u-1/reject").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/drivers/d-1/verification/items/INSURANCE/request-resubmission").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        assertThat(lastPath.get()).isNull();
    }

    @Test
    void rejectingWhatSmileIdApprovedComesBackAsTheOwningServicesFourHundred() throws Exception {
        replies.put("/profile/identity-verification/people/u-1/reject", new Reply(400, "{\"success\":false,\"message\":"
                + "\"Smile ID verified this identity, so it stays approved - only an approval on a partial name match that hasn't been reviewed yet can be rejected\"}"));
        replies.put("/profile/drivers/d-1/verification/items/GOVERNMENT_ID/request-resubmission", new Reply(400, "{\"success\":false,"
                + "\"message\":\"Smile ID verified this driver's licence, so it stays approved\"}"));
        String token = bearer(UserRole.OPS_ADMIN);

        mockMvc.perform(post("/identity-verification/people/u-1/reject").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Looks wrong\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.startsWith("Smile ID verified this identity")));
        mockMvc.perform(post("/drivers/d-1/verification/items/GOVERNMENT_ID/request-resubmission").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Blurry\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Smile ID verified this driver's licence, so it stays approved"));
    }

    @Test
    void identityAndDocumentActionsAreForOpsAdminsOnly() throws Exception {
        mockMvc.perform(get("/identity-verification/people")).andExpect(status().isUnauthorized());
        for (UserRole role : List.of(UserRole.RIDER, UserRole.DRIVER)) {
            String token = bearer(role);
            mockMvc.perform(get("/identity-verification/people").header("Authorization", token)).andExpect(status().isForbidden());
            mockMvc.perform(get("/identity-verification/people/u-1").header("Authorization", token)).andExpect(status().isForbidden());
            mockMvc.perform(post("/identity-verification/people/u-1/approve").header("Authorization", token)).andExpect(status().isForbidden());
            mockMvc.perform(post("/identity-verification/people/u-1/reject").header("Authorization", token)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}")).andExpect(status().isForbidden());
            mockMvc.perform(post("/identity-verification/people/u-1/reset-attempts").header("Authorization", token)).andExpect(status().isForbidden());
            mockMvc.perform(post("/drivers/d-1/verification/items/INSURANCE/approve").header("Authorization", token)).andExpect(status().isForbidden());
            mockMvc.perform(post("/drivers/d-1/verification/items/INSURANCE/request-resubmission").header("Authorization", token)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}")).andExpect(status().isForbidden());
        }
        assertThat(paths).as("a refused caller never reaches profile-service").isEmpty();
    }

    // ---- per-document approve / request resubmission ------------------------------------------------------------

    @Test
    void approvingOneDocumentReturnsTheReviewPageAndIsSignedByTheAdmin() throws Exception {
        replies.put("/profile/drivers/d-1/verification/items/GOVERNMENT_ID/approve", new Reply(200, STATUS_JSON));
        replies.put("/profile/drivers/d-1/verification/review", new Reply(200, REVIEW_JSON));

        mockMvc.perform(post("/drivers/d-1/verification/items/GOVERNMENT_ID/approve").header("Authorization", bearer(UserRole.OPS_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"Matched the licence in person\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Driver's licence approved"))
                .andExpect(jsonPath("$.data.review.items[0].mark").value("FLAGGED"))
                .andExpect(jsonPath("$.data.driver.ref").value("D-D1"));

        Request sent = requests.stream().filter(r -> r.path().endsWith("/approve")).findFirst().orElseThrow();
        JsonNode body = objectMapper.readTree(sent.body());
        assertThat(body.get("note").asText()).isEqualTo("Matched the licence in person");
        assertThat(body.get("reviewedBy").asText()).isEqualTo("Ada Ops");
        mockMvc.perform(post("/drivers/d-1/verification/items/PASSPORT/approve").header("Authorization", bearer(UserRole.OPS_ADMIN)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void askingForADocumentAgainForwardsTheReasonAndShowsTheRequest() throws Exception {
        replies.put("/profile/drivers/d-1/verification/items/INSURANCE/request-resubmission", new Reply(200,
                STATUS_JSON.replace("\"overallStatus\":\"IN_REVIEW\"", "\"overallStatus\":\"REJECTED\",\"resubmissionRequests\":[{"
                        + "\"item\":\"INSURANCE\",\"label\":\"Insurance certificate\",\"reason\":\"Cut off\",\"requestedAt\":\"2026-10-04T09:00:00Z\"}]")));
        replies.put("/profile/drivers/d-1/verification/review", new Reply(200, REVIEW_JSON));

        mockMvc.perform(post("/drivers/d-1/verification/items/INSURANCE/request-resubmission").header("Authorization", bearer(UserRole.OPS_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Cut off\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Resubmission requested"))
                .andExpect(jsonPath("$.data.resubmissionRequests[0].item").value("INSURANCE"))
                .andExpect(jsonPath("$.data.resubmissionRequests[0].reason").value("Cut off"));

        Request sent = requests.stream().filter(r -> r.path().endsWith("/request-resubmission")).findFirst().orElseThrow();
        assertThat(objectMapper.readTree(sent.body()).get("reviewedBy").asText()).isEqualTo("Ada Ops");
    }

    // ---- Phase 2: account management - see docs/ops-console/accounts.md ---------------------------------------------

    @Test
    void accountActionsAreForOpsAdminsOnlyAndNeedAReason() throws Exception {
        mockMvc.perform(post("/users/u-1/suspend").header("Authorization", bearer(UserRole.RIDER))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}")).andExpect(status().isForbidden());
        mockMvc.perform(post("/users/u-1/sign-out-everywhere").header("Authorization", bearer(UserRole.DRIVER))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}")).andExpect(status().isForbidden());
        mockMvc.perform(patch("/users/u-1/details").header("Authorization", bearer(UserRole.RIDER))
                .contentType(MediaType.APPLICATION_JSON).content("{\"firstName\":\"A\",\"reason\":\"x\"}")).andExpect(status().isForbidden());
        mockMvc.perform(get("/users/u-1/account-history").header("Authorization", bearer(UserRole.DRIVER))).andExpect(status().isForbidden());
        mockMvc.perform(get("/account-deletions").header("Authorization", bearer(UserRole.RIDER))).andExpect(status().isForbidden());
        mockMvc.perform(get("/account-deletions")).andExpect(status().isUnauthorized());

        String ops = bearer(UserRole.OPS_ADMIN);
        mockMvc.perform(post("/users/u-1/reinstate").header("Authorization", ops).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("reason: is required"));
        mockMvc.perform(post("/users/u-1/suspend").header("Authorization", ops).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"  \"}")).andExpect(status().isBadRequest());
        mockMvc.perform(post("/users/u-1/sign-out-everywhere").header("Authorization", ops).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isBadRequest());
        mockMvc.perform(patch("/users/u-1/details").header("Authorization", ops).contentType(MediaType.APPLICATION_JSON)
                .content("{\"firstName\":\"Ada\"}")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("reason: is required"));
        mockMvc.perform(patch("/drivers/d-1/vehicles/v-1").header("Authorization", ops).contentType(MediaType.APPLICATION_JSON)
                .content("{\"plateNumber\":\"KJA1\"}")).andExpect(status().isBadRequest());
        assertThat(paths).as("nothing refused reaches another service").isEmpty();
    }

    @Test
    void suspendReinstateAndSignOutGoToIdentityWithTheAdminAndTheReason() throws Exception {
        String ops = bearer(UserRole.OPS_ADMIN);
        nextBody.set("{\"success\":true,\"data\":null}");

        mockMvc.perform(post("/users/u-1/suspend").header("Authorization", ops).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Threatened a rider\"}")).andExpect(status().isOk());
        mockMvc.perform(post("/users/u-1/reinstate").header("Authorization", ops).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Investigated - CCTV cleared him\"}")).andExpect(status().isOk());
        mockMvc.perform(post("/users/u-1/sign-out-everywhere").header("Authorization", ops).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Lost phone\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.message").value("Signed out everywhere"));

        assertThat(requests).extracting(Request::path).containsExactly("/identity/internal/users/u-1/suspend",
                "/identity/internal/users/u-1/reinstate", "/identity/internal/users/u-1/sign-out-everywhere");
        JsonNode reinstate = objectMapper.readTree(requests.get(1).body());
        assertThat(reinstate.get("reason").asText()).isEqualTo("Investigated - CCTV cleared him");
        assertThat(reinstate.get("opsUserId").asText()).isEqualTo("admin-1");
        assertThat(reinstate.get("opsUserName").asText()).isEqualTo("Ada Ops");
        assertThat(lastAuth.get()).isEqualTo(ops);

        // TEMPORARY compatibility: the console on main posts reinstate with no body - accepted and recorded as such.
        mockMvc.perform(post("/users/u-1/reinstate").header("Authorization", ops)).andExpect(status().isOk());
        JsonNode legacy = objectMapper.readTree(requests.get(requests.size() - 1).body());
        assertThat(legacy.get("reason").asText()).isEqualTo(UserController.LEGACY_NO_REASON);

        replies.put("/identity/internal/users/u-1/reinstate", new Reply(400, "{\"success\":false,\"message\":\"This account isn't suspended\"}"));
        mockMvc.perform(post("/users/u-1/reinstate").header("Authorization", ops).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"again\"}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("This account isn't suspended"));
    }

    @Test
    void aPhoneNumberCantBeEditedByOps() throws Exception {
        for (String body : List.of("{\"phone\":\"08099998888\",\"reason\":\"New number\"}",
                "{\"firstName\":\"Ada\",\"phoneNumber\":\"08099998888\",\"reason\":\"New number\"}")) {
            mockMvc.perform(patch("/users/u-1/details").header("Authorization", bearer(UserRole.OPS_ADMIN))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Phone numbers can't be changed from the ops console - the phone number is "
                            + "how the person signs in, so only they can change it"));
        }
        assertThat(paths).isEmpty();
    }

    @Test
    void aDetailsEditIsMadeInProfileServiceAndRecordedInTheAccountHistory() throws Exception {
        replies.put("/profile/internal/users/u-1/details", new Reply(200, "{\"success\":true,\"data\":{\"changes\":["
                + "{\"field\":\"lastName\",\"before\":\"Okafr\",\"after\":\"Okafor\"}],\"vehicle\":null,\"sentBackToReview\":false}}"));
        replies.put("/identity/internal/users/u-1/account-actions", new Reply(200, "{\"success\":true,\"data\":{}}"));

        mockMvc.perform(patch("/users/u-1/details").header("Authorization", bearer(UserRole.OPS_ADMIN)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lastName\":\"Okafor\",\"reason\":\"Typo - checked against the licence\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.changes[0].field").value("lastName"))
                .andExpect(jsonPath("$.data.changes[0].before").value("Okafr"))
                .andExpect(jsonPath("$.data.historyRecorded").value(true));

        assertThat(requests).extracting(Request::method).containsExactly("PATCH", "POST");
        assertThat(objectMapper.readTree(requests.get(0).body()).get("lastName").asText()).isEqualTo("Okafor");
        JsonNode recorded = objectMapper.readTree(requests.get(1).body());
        assertThat(recorded.get("action").asText()).isEqualTo("DETAILS_EDITED");
        assertThat(recorded.get("reason").asText()).isEqualTo("Typo - checked against the licence");
        assertThat(recorded.at("/changes/0/after").asText()).isEqualTo("Okafor");
        assertThat(recorded.get("opsUserName").asText()).isEqualTo("Ada Ops");

        replies.put("/identity/internal/users/u-1/account-actions", new Reply(503, "{\"success\":false,\"message\":\"down\"}"));
        mockMvc.perform(patch("/users/u-1/details").header("Authorization", bearer(UserRole.OPS_ADMIN)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lastName\":\"Okafor\",\"reason\":\"Typo\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.historyRecorded").value(false));

        replies.put("/profile/internal/users/u-1/details", new Reply(400,
                "{\"success\":false,\"message\":\"Nothing to change - these are already the details on the account\"}"));
        mockMvc.perform(patch("/users/u-1/details").header("Authorization", bearer(UserRole.OPS_ADMIN)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lastName\":\"Okafor\",\"reason\":\"Typo\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Nothing to change - these are already the details on the account"));
    }

    @Test
    void aVehicleEditAndALicenceResetAreRecordedTooAndSoIsAnIdentityReset() throws Exception {
        replies.put("/profile/internal/drivers/d-1/vehicles/v-1", new Reply(200, "{\"success\":true,\"data\":{\"changes\":["
                + "{\"field\":\"plateNumber\",\"before\":\"LND-1\",\"after\":\"KJA-100-AA\"}],"
                + "\"vehicle\":{\"id\":\"v-1\",\"plateNumber\":\"KJA-100-AA\",\"status\":\"IN_REVIEW\"},\"sentBackToReview\":true}}"));
        replies.put("/profile/drivers/d-1/verification/licence/reset-attempts", new Reply(200,
                "{\"success\":true,\"data\":{\"used\":0,\"max\":5,\"left\":5,\"lockedOut\":false,\"canReset\":false}}"));
        replies.put("/profile/identity-verification/people/d-1/reset-attempts", new Reply(200, "{\"success\":true,\"data\":{\"userId\":\"d-1\"}}"));
        replies.put("/identity/internal/users/d-1/account-actions", new Reply(200, "{\"success\":true,\"data\":{}}"));
        String ops = bearer(UserRole.OPS_ADMIN);

        mockMvc.perform(patch("/drivers/d-1/vehicles/v-1").header("Authorization", ops).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"plateNumber\":\"KJA-100-AA\",\"reason\":\"Driver re-registered the car\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sentBackToReview").value(true))
                .andExpect(jsonPath("$.data.vehicle.status").value("IN_REVIEW"));
        assertThat(paths).anyMatch(p -> p.startsWith("/profile/internal/drivers/d-1/vehicles/v-1?") && p.contains("opsUserId=admin-1"));
        assertThat(objectMapper.readTree(requests.get(0).body()).get("plateNumber").asText()).isEqualTo("KJA-100-AA");

        mockMvc.perform(post("/drivers/d-1/verification/licence/reset-attempts").header("Authorization", ops)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"Camera was broken\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.left").value(5));
        mockMvc.perform(post("/identity-verification/people/d-1/reset-attempts").header("Authorization", ops)
                .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"Bad light\"}")).andExpect(status().isOk());

        List<JsonNode> recorded = new java.util.ArrayList<>();
        for (Request r : requests) {
            if (r.path().equals("/identity/internal/users/d-1/account-actions")) {
                recorded.add(objectMapper.readTree(r.body()));
            }
        }
        assertThat(recorded).extracting(n -> n.get("action").asText())
                .containsExactly("VEHICLE_EDITED", "LICENCE_ATTEMPTS_RESET", "IDENTITY_ATTEMPTS_RESET");
        assertThat(recorded.get(0).get("reason").asText()).isEqualTo("Driver re-registered the car");
        assertThat(recorded.get(1).get("reason").asText()).isEqualTo("Camera was broken");
    }

    @Test
    void historyAndDeletionRequestsArePassedThrough() throws Exception {
        replies.put("/identity/internal/users/u-1/account-actions", new Reply(200, "{\"success\":true,\"data\":{\"content\":["
                + "{\"id\":\"a-1\",\"action\":\"SUSPENDED\",\"actor\":{\"id\":\"admin-1\",\"name\":\"Ada Ops\"},\"reason\":\"x\",\"changes\":[],"
                + "\"at\":\"2026-11-01T10:00:00Z\"}],\"page\":1,\"size\":5,\"totalElements\":6,\"totalPages\":2,\"last\":true}}"));
        replies.put("/identity/internal/account-deletions", new Reply(200, "{\"success\":true,\"data\":{\"content\":["
                + "{\"id\":\"del-1\",\"status\":\"PENDING\",\"blockers\":[\"you're on a trip right now\"]}],\"page\":0,\"size\":20,"
                + "\"totalElements\":1,\"totalPages\":1,\"last\":true}}"));
        replies.put("/identity/internal/account-deletions/del-1", new Reply(200, "{\"success\":true,\"data\":{\"id\":\"del-1\",\"status\":\"PENDING\"}}"));
        replies.put("/identity/internal/account-deletions/nope", new Reply(404, "{\"success\":false,\"message\":\"Account deletion request not found\"}"));
        String ops = bearer(UserRole.OPS_ADMIN);

        mockMvc.perform(get("/users/u-1/account-history?page=1&size=5").header("Authorization", ops))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.content[0].action").value("SUSPENDED"))
                .andExpect(jsonPath("$.data.totalElements").value(6));
        assertThat(paths).anyMatch(p -> p.startsWith("/identity/internal/users/u-1/account-actions?") && p.contains("page=1") && p.contains("size=5"));

        mockMvc.perform(get("/account-deletions?status=PENDING").header("Authorization", ops))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.content[0].blockers[0]").value("you're on a trip right now"));
        assertThat(paths).anyMatch(p -> p.startsWith("/identity/internal/account-deletions?") && p.contains("status=PENDING"));
        mockMvc.perform(get("/account-deletions/del-1").header("Authorization", ops)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"));
        mockMvc.perform(get("/account-deletions/nope").header("Authorization", ops)).andExpect(status().isNotFound());
        mockMvc.perform(get("/account-deletions?status=SOON").header("Authorization", ops)).andExpect(status().isBadRequest());
    }

    @Test
    void aDriversProfileIsCompleteInOneCallAndSurvivesAServiceBeingDown() throws Exception {
        replies.put("/profile/internal/drivers/index/dddd4444-4", new Reply(200, "{\"data\":{\"driverId\":\"dddd4444-4\","
                + "\"firstName\":\"Aisha\",\"lastName\":\"Lawal\",\"phone\":\"08055556666\",\"joinedAt\":\"2026-02-01T09:00:00Z\","
                + "\"status\":\"SUSPENDED\",\"verificationStatus\":\"APPROVED\",\"suspendedAt\":\"2026-10-01T10:00:00Z\","
                + "\"suspensionReason\":\"Threatened a rider\",\"plateNumber\":\"EKY-9-ZZ\"}}"));
        replies.put("/identity/internal/users/dddd4444-4/account", new Reply(200, "{\"data\":{\"status\":\"SUSPENDED\",\"roles\":[\"DRIVER\",\"RIDER\"],"
                + "\"lastSeenAt\":\"2026-10-01T09:00:00Z\",\"sessionsValidAfter\":\"2026-10-01T10:00:00Z\",\"phoneVerifiedAt\":\"2026-02-01T09:00:00Z\","
                + "\"devices\":[{\"deviceId\":\"p-1\",\"deviceName\":\"Aisha's Pixel\",\"platform\":\"ANDROID\",\"signedIn\":false}],"
                + "\"suspension\":{\"suspendedAt\":\"2026-10-01T10:00:00Z\",\"reason\":\"Threatened a rider\",\"suspendedBy\":{\"id\":\"admin-9\",\"name\":\"Bola Ops\"}},"
                + "\"suspensionHistory\":[{\"suspendedAt\":\"2026-03-01T10:00:00Z\",\"reason\":\"Late\",\"reinstatedAt\":\"2026-03-02T10:00:00Z\","
                + "\"reinstateReason\":\"Apologised\"},{\"suspendedAt\":\"2026-10-01T10:00:00Z\",\"reason\":\"Threatened a rider\"}],"
                + "\"pendingDeletion\":null}}"));
        replies.put("/profile/internal/users/dddd4444-4/ops-profile", new Reply(200, "{\"data\":{\"userId\":\"dddd4444-4\","
                + "\"driver\":{\"firstName\":\"Aisha Bello\",\"lastName\":\"Lawal\",\"email\":\"aisha@x.ng\",\"verificationStatus\":\"APPROVED\",\"verifiedSince\":\"2026-02-03T09:00:00Z\","
                + "\"items\":[{\"item\":\"GOVERNMENT_ID\",\"status\":\"APPROVED\"}],\"licenceAttempts\":{\"used\":1,\"left\":4}},"
                + "\"rider\":{\"email\":\"aisha@x.ng\"},\"vehicles\":[{\"id\":\"v-1\",\"plateNumber\":\"EKY-9-ZZ\",\"seatCapacity\":4,\"primary\":true}],"
                + "\"emergencyContacts\":[{\"id\":\"c-1\",\"name\":\"Musa\",\"phone\":\"08011112222\",\"primary\":true}],"
                + "\"identity\":{\"status\":\"APPROVED\"}}}"));
        replies.put("/identity/internal/users/dddd4444-4/account-actions", new Reply(200, "{\"data\":{\"content\":["
                + "{\"action\":\"SUSPENDED\",\"reason\":\"Threatened a rider\"}],\"totalElements\":3}}"));
        String ops = bearer(UserRole.OPS_ADMIN);

        mockMvc.perform(get("/drivers/dddd4444-4").header("Authorization", ops))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.suspension.reason").value("Threatened a rider"))
                .andExpect(jsonPath("$.data.suspension.suspendedBy.name").value("Bola Ops"))
                .andExpect(jsonPath("$.data.account.ref").value("D-DDDD44"))
                .andExpect(jsonPath("$.data.account.status").value("SUSPENDED"))
                .andExpect(jsonPath("$.data.account.roles[1]").value("RIDER"))
                .andExpect(jsonPath("$.data.account.lastSeenAt").value("2026-10-01T09:00:00Z"))
                .andExpect(jsonPath("$.data.account.devices[0].deviceName").value("Aisha's Pixel"))
                .andExpect(jsonPath("$.data.email").value("aisha@x.ng"))
                // the stored parts, not a split of the display name - a two-word first name stays whole
                .andExpect(jsonPath("$.data.firstName").value("Aisha Bello"))
                .andExpect(jsonPath("$.data.lastName").value("Lawal"))
                .andExpect(jsonPath("$.data.verification.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.verification.licenceAttempts.left").value(4))
                .andExpect(jsonPath("$.data.verification.identity.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.vehicles[0].seatCapacity").value(4))
                .andExpect(jsonPath("$.data.emergencyContacts[0].name").value("Musa"))
                .andExpect(jsonPath("$.data.suspensionHistory[0].reinstateReason").value("Apologised"))
                .andExpect(jsonPath("$.data.recentActions[0].action").value("SUSPENDED"));

        replies.put("/identity/internal/users/dddd4444-4/account", new Reply(503, "{\"success\":false,\"message\":\"down\"}"));
        replies.put("/identity/internal/users/dddd4444-4/account-actions", new Reply(503, "{\"success\":false,\"message\":\"down\"}"));
        mockMvc.perform(get("/drivers/dddd4444-4").header("Authorization", ops))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.suspension.reason").value("Threatened a rider"))
                .andExpect(jsonPath("$.data.account.status").doesNotExist())
                .andExpect(jsonPath("$.data.vehicles[0].plateNumber").value("EKY-9-ZZ"));
    }

    @Test
    void aRidersProfileHasContactsIdentityAndHistoryToo() throws Exception {
        replies.put("/profile/internal/riders/index/r-uuid-1", new Reply(200, "{\"data\":" + RIDER_ROW + "}"));
        replies.put("/identity/internal/users/r-uuid-1/account", new Reply(200, "{\"data\":{\"status\":\"ACTIVE\",\"roles\":[\"RIDER\"],"
                + "\"devices\":[],\"suspensionHistory\":[],\"pendingDeletion\":{\"requestId\":\"del-1\",\"blockers\":[\"refund pending\"]}}}"));
        replies.put("/profile/internal/users/r-uuid-1/ops-profile", new Reply(200, "{\"data\":{\"rider\":{\"email\":\"r@x.ng\"},"
                + "\"vehicles\":[],\"emergencyContacts\":[{\"name\":\"Ngozi\"}],\"identity\":{\"status\":\"IN_REVIEW\"}}}"));
        replies.put("/identity/internal/users/r-uuid-1/account-actions", new Reply(200, "{\"data\":{\"content\":[]}}"));

        mockMvc.perform(get("/riders/r-uuid-1").header("Authorization", bearer(UserRole.OPS_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value("r@x.ng"))
                .andExpect(jsonPath("$.data.account.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.verification.identity.status").value("IN_REVIEW"))
                .andExpect(jsonPath("$.data.emergencyContacts[0].name").value("Ngozi"))
                .andExpect(jsonPath("$.data.pendingDeletion.blockers[0]").value("refund pending"))
                .andExpect(jsonPath("$.data.activity.totalTrips").value(213));
    }

    @Test
    void aTokenRevokedInIdentityIsRefusedHereAndOneIssuedAfterwardsWorks() throws Exception {
        replies.put("/profile/drivers/verification/queue", new Reply(200, "{\"data\":{\"content\":[],\"page\":0,\"size\":20,"
                + "\"totalElements\":0,\"totalPages\":0,\"last\":true}}"));
        String before = bearer(UserRole.OPS_ADMIN);
        Thread.sleep(5);
        java.time.Instant cut = java.time.Instant.now();
        replies.put("/identity/internal/users/token-revocations", new Reply(200, "{\"success\":true,\"data\":["
                + "{\"userId\":\"admin-1\",\"tokensValidAfter\":\"" + cut + "\",\"suspended\":false,\"reason\":\"SIGNED_OUT_EVERYWHERE\","
                + "\"stateAt\":\"" + cut + "\"},"
                + "{\"userId\":\"admin-2\",\"tokensValidAfter\":\"" + cut + "\",\"suspended\":true,\"stateAt\":\"" + cut + "\"}]}"));
        assertThat(revocationBootstrap.loadOnce()).isTrue();
        Thread.sleep(5);
        String after = bearer(UserRole.OPS_ADMIN);

        mockMvc.perform(get("/drivers/verification/queue").header("Authorization", before))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Your session has ended. Please sign in again."));
        mockMvc.perform(get("/drivers/verification/queue").header("Authorization", after)).andExpect(status().isOk());
        mockMvc.perform(get("/drivers/verification/queue").header("Authorization",
                        "Bearer " + jwtUtil.generateToken("admin-2", null, "Bola", "Ops", UserRole.OPS_ADMIN)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("This account has been suspended. Contact CoRyd support."));
        assertThat(paths).as("the snapshot came from identity with the internal key and a system token")
                .contains("/identity/internal/users/token-revocations");
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
