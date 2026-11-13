package org.coryd.ops.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.coryd.common.enums.*;
import org.coryd.common.security.JwtUtil;
import org.coryd.ops.dto.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/** Driver verification and people/vehicle details live in profile-service. Calls carry the ops admin's own
 * token, so profile-service's OPS_ADMIN checks still decide who may approve - this service adds no second gate. */
@Component
public class ProfileServiceClient {

    private static final String SERVICE = "profile-service";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public ProfileServiceClient(@Value("${services.profile.url}") String profileServiceUrl,
                                @Value("${services.internal.api-key}") String internalApiKey,
                                JwtUtil jwtUtil, ObjectMapper objectMapper) {
        this.restClient = OpsRestClients.build(profileServiceUrl, internalApiKey, jwtUtil);
        this.objectMapper = objectMapper;
    }

    public QueuePage queue(VerificationQueueTab tab, String search, int page, int size) {
        return queue(tab, search, page, size, null, null);
    }

    public QueuePage queue(VerificationQueueTab tab, String search, int page, int size,
                           org.coryd.common.enums.VerificationQueueSort sort, org.springframework.data.domain.Sort.Direction direction) {
        return DownstreamCalls.data(SERVICE, objectMapper, QueuePage.class, () -> restClient.get()
                .uri(b -> {
                    b.path("/drivers/verification/queue").queryParam("page", page).queryParam("size", size);
                    if (sort != null) {
                        b.queryParam("sort", sort);
                    }
                    if (direction != null) {
                        b.queryParam("direction", direction);
                    }
                    if (tab != null) {
                        b.queryParam("tab", tab);
                    }
                    if (search != null && !search.isBlank()) {
                        b.queryParam("search", search);
                    }
                    return b.build();
                })
                .retrieve()
                .body(JsonNode.class));
    }

    public VerificationQueueCountsResponse queueCounts(String search) {
        return DownstreamCalls.data(SERVICE, objectMapper, VerificationQueueCountsResponse.class, () -> restClient.get()
                .uri(b -> {
                    b.path("/drivers/verification/queue/counts");
                    if (search != null && !search.isBlank()) {
                        b.queryParam("search", search);
                    }
                    return b.build();
                })
                .retrieve()
                .body(JsonNode.class));
    }

    public VerificationStatusResponse verificationStatus(String driverId) {
        return DownstreamCalls.data(SERVICE, objectMapper, VerificationStatusResponse.class, () -> restClient.get()
                .uri("/drivers/{driverId}/verification-status", driverId)
                .retrieve()
                .body(JsonNode.class));
    }

    public VerificationStatusResponse decideVerification(String driverId, List<ItemDecision> decisions, String reviewedBy) {
        return DownstreamCalls.data(SERVICE, objectMapper, VerificationStatusResponse.class, () -> restClient.patch()
                .uri("/drivers/{driverId}/verification", driverId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new DecisionBatch(decisions, reviewedBy))
                .retrieve()
                .body(JsonNode.class));
    }

    /** The reviewer's marks on a driver's items - profile-service checks the forwarded token is OPS_ADMIN. */
    public org.coryd.ops.dto.VerificationReview verificationReview(String driverId) {
        return DownstreamCalls.data(SERVICE, objectMapper, org.coryd.ops.dto.VerificationReview.class, () -> restClient.get()
                .uri("/drivers/{driverId}/verification/review", driverId)
                .retrieve()
                .body(JsonNode.class));
    }

    public org.coryd.ops.dto.VerificationReview markVerificationItem(String driverId, ReviewableItem item,
                                                                    ReviewMark mark, String note, String markedBy) {
        return DownstreamCalls.data(SERVICE, objectMapper, org.coryd.ops.dto.VerificationReview.class, () -> restClient.put()
                .uri("/drivers/{driverId}/verification/items/{item}/review", driverId, item)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new MarkBody(mark, note, markedBy))
                .retrieve()
                .body(JsonNode.class));
    }

    public org.coryd.ops.dto.VerificationReview resetVerificationItem(String driverId, ReviewableItem item) {
        return DownstreamCalls.data(SERVICE, objectMapper, org.coryd.ops.dto.VerificationReview.class, () -> restClient.delete()
                .uri("/drivers/{driverId}/verification/items/{item}/review", driverId, item)
                .retrieve()
                .body(JsonNode.class));
    }

    public VerificationStatusResponse approveVerification(String driverId, String note, String reviewedBy) {
        return DownstreamCalls.data(SERVICE, objectMapper, VerificationStatusResponse.class, () -> restClient.post()
                .uri("/drivers/{driverId}/verification/approve", driverId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ReviewBody(note, reviewedBy))
                .retrieve()
                .body(JsonNode.class));
    }

    public VerificationStatusResponse saveVerification(String driverId, String reviewedBy) {
        return DownstreamCalls.data(SERVICE, objectMapper, VerificationStatusResponse.class, () -> restClient.post()
                .uri("/drivers/{driverId}/verification/save", driverId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ReviewBody(null, reviewedBy))
                .retrieve()
                .body(JsonNode.class));
    }

    public VerificationStatusResponse rejectVerification(String driverId, String reviewedBy) {
        return DownstreamCalls.data(SERVICE, objectMapper, VerificationStatusResponse.class, () -> restClient.post()
                .uri("/drivers/{driverId}/verification/reject", driverId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ReviewBody(null, reviewedBy))
                .retrieve()
                .body(JsonNode.class));
    }

    /** The licence as Smile ID saw it - passed through as profile-service shapes it. */
    public JsonNode licenceReview(String driverId) {
        return DownstreamCalls.data(SERVICE, objectMapper, () -> restClient.get()
                .uri("/drivers/{driverId}/verification/licence", driverId)
                .retrieve()
                .body(JsonNode.class));
    }

    public List<VerificationDecisionHistoryItem> verificationHistory(String driverId) {
        VerificationDecisionHistoryItem[] items = DownstreamCalls.data(SERVICE, objectMapper,
                VerificationDecisionHistoryItem[].class, () -> restClient.get()
                        .uri("/drivers/{driverId}/verification/history", driverId)
                        .retrieve()
                        .body(JsonNode.class));
        return items == null ? List.of() : java.util.Arrays.asList(items);
    }

    /** People whose identity check waits on ops - profile-service checks the forwarded token is OPS_ADMIN. */
    public JsonNode identityVerificationQueue(int page, int size) {
        return DownstreamCalls.data(SERVICE, objectMapper, () -> restClient.get()
                .uri(b -> b.path("/identity-verification/queue").queryParam("page", page).queryParam("size", size).build())
                .retrieve()
                .body(JsonNode.class));
    }

    /** Approvals on a partial name match not yet reviewed - profile-service checks the forwarded token is OPS_ADMIN. */
    public JsonNode partialNameMatches(int page, int size) {
        return DownstreamCalls.data(SERVICE, objectMapper, () -> restClient.get()
                .uri(b -> b.path("/identity-verification/partial-name-matches").queryParam("page", page).queryParam("size", size).build())
                .retrieve()
                .body(JsonNode.class));
    }

    public void markPartialNameMatchReviewed(String checkId) {
        DownstreamCalls.data(SERVICE, objectMapper, () -> restClient.post()
                .uri("/identity-verification/partial-name-matches/{checkId}/reviewed", checkId)
                .retrieve()
                .body(JsonNode.class));
    }

    public JsonNode decideIdentityVerification(String userId, boolean approve, String reason) {
        Map<String, Object> body = new HashMap<>();
        body.put("approve", approve);
        body.put("reason", reason);
        return DownstreamCalls.data(SERVICE, objectMapper, () -> restClient.patch()
                .uri("/identity-verification/{userId}", userId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class));
    }

    /** The identity (NIN) list - filtered, sorted and paged by profile-service, which checks the token is OPS_ADMIN. */
    public JsonNode identityPeople(IdentityReviewTab tab, String search, IdentityReviewRole role, IdentityReviewSort sort,
                                   Sort.Direction direction, int page, int size) {
        return DownstreamCalls.data(SERVICE, objectMapper, () -> restClient.get()
                .uri(b -> {
                    b.path("/identity-verification/people").queryParam("page", page).queryParam("size", size);
                    if (tab != null) {
                        b.queryParam("tab", tab);
                    }
                    if (search != null && !search.isBlank()) {
                        b.queryParam("search", search);
                    }
                    if (role != null) {
                        b.queryParam("role", role);
                    }
                    if (sort != null) {
                        b.queryParam("sort", sort);
                    }
                    if (direction != null) {
                        b.queryParam("direction", direction);
                    }
                    return b.build();
                })
                .retrieve()
                .body(JsonNode.class));
    }

    public JsonNode identityPeopleCounts(String search, IdentityReviewRole role) {
        return DownstreamCalls.data(SERVICE, objectMapper, () -> restClient.get()
                .uri(b -> {
                    b.path("/identity-verification/people/counts");
                    if (search != null && !search.isBlank()) {
                        b.queryParam("search", search);
                    }
                    if (role != null) {
                        b.queryParam("role", role);
                    }
                    return b.build();
                })
                .retrieve()
                .body(JsonNode.class));
    }

    public JsonNode identityPerson(String userId) {
        return DownstreamCalls.data(SERVICE, objectMapper, () -> restClient.get()
                .uri("/identity-verification/people/{userId}", userId)
                .retrieve()
                .body(JsonNode.class));
    }

    public JsonNode identityPersonHistory(String userId) {
        return DownstreamCalls.data(SERVICE, objectMapper, () -> restClient.get()
                .uri("/identity-verification/people/{userId}/history", userId)
                .retrieve()
                .body(JsonNode.class));
    }

    public JsonNode identityCheckImages(String userId, String checkId) {
        return DownstreamCalls.data(SERVICE, objectMapper, () -> restClient.get()
                .uri("/identity-verification/people/{userId}/checks/{checkId}/images", userId, checkId)
                .retrieve()
                .body(JsonNode.class));
    }

    /** action is approve, reject or reset-attempts. reviewedBy is the signed-in admin's name - never taken from the console. */
    public JsonNode identityAction(String userId, String action, String note, String reason, String reviewedBy) {
        Map<String, Object> body = new HashMap<>();
        body.put("note", note);
        body.put("reason", reason);
        body.put("reviewedBy", reviewedBy);
        return DownstreamCalls.data(SERVICE, objectMapper, () -> restClient.post()
                .uri("/identity-verification/people/{userId}/{action}", userId, action)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class));
    }

    /** Gives a driver locked out of the licence check their tries back. Returns their licence tries now. */
    public JsonNode resetLicenceAttempts(String driverId, String note, String reviewedBy) {
        Map<String, Object> body = new HashMap<>();
        body.put("note", note);
        body.put("reviewedBy", reviewedBy);
        return DownstreamCalls.data(SERVICE, objectMapper, () -> restClient.post()
                .uri("/drivers/{driverId}/verification/licence/reset-attempts", driverId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class));
    }

    /** Everything profile-service holds about one person, for the account profile screens. */
    public JsonNode accountProfile(String userId) {
        return DownstreamCalls.data(SERVICE, objectMapper, () -> restClient.get()
                .uri("/internal/users/{userId}/ops-profile", userId)
                .retrieve()
                .body(JsonNode.class));
    }

    /** Name / email. Returns {changes:[{field,before,after}], ...}. */
    public JsonNode editDetails(String userId, String firstName, String lastName, String email) {
        Map<String, Object> body = new HashMap<>();
        body.put("firstName", firstName);
        body.put("lastName", lastName);
        body.put("email", email);
        return DownstreamCalls.data(SERVICE, objectMapper, () -> restClient.patch()
                .uri("/internal/users/{userId}/details", userId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class));
    }

    /** A driver's vehicle. Returns {changes, vehicle, sentBackToReview}. */
    public JsonNode editVehicle(String driverId, String vehicleId, Map<String, Object> fields, String opsUserId, String opsUserName) {
        return DownstreamCalls.data(SERVICE, objectMapper, () -> restClient.patch()
                .uri(b -> b.path("/internal/drivers/{driverId}/vehicles/{vehicleId}")
                        .queryParam("opsUserId", opsUserId).queryParam("opsUserName", opsUserName).build(driverId, vehicleId))
                .contentType(MediaType.APPLICATION_JSON)
                .body(fields)
                .retrieve()
                .body(JsonNode.class));
    }

    /** Approve one driver document - including over Smile ID's failed licence check. */
    public VerificationStatusResponse approveVerificationItem(String driverId, ReviewableItem item, String note, String reviewedBy) {
        Map<String, Object> body = new HashMap<>();
        body.put("note", note);
        body.put("reviewedBy", reviewedBy);
        return DownstreamCalls.data(SERVICE, objectMapper, VerificationStatusResponse.class, () -> restClient.post()
                .uri("/drivers/{driverId}/verification/items/{item}/approve", driverId, item)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class));
    }

    /** Ask the driver to upload one document again, with the reason they'll see. */
    public VerificationStatusResponse requestResubmission(String driverId, ReviewableItem item, String reason, String reviewedBy) {
        Map<String, Object> body = new HashMap<>();
        body.put("reason", reason);
        body.put("reviewedBy", reviewedBy);
        return DownstreamCalls.data(SERVICE, objectMapper, VerificationStatusResponse.class, () -> restClient.post()
                .uri("/drivers/{driverId}/verification/items/{item}/request-resubmission", driverId, item)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class));
    }

    /** Display details only - a profile-service hiccup blanks names rather than failing the whole page. */
    public Map<String, Driver> drivers(Collection<String> userIds) {
        return DownstreamCalls.batch(SERVICE, objectMapper, userIds, Driver.class, Driver::userId, ids -> restClient.get()
                .uri(b -> b.path("/internal/drivers/batch").queryParam("userIds", ids).build())
                .retrieve()
                .body(JsonNode.class));
    }

    public Map<String, Vehicle> vehicles(Collection<String> vehicleIds) {
        return DownstreamCalls.batch(SERVICE, objectMapper, vehicleIds, Vehicle.class, Vehicle::id, ids -> restClient.get()
                .uri(b -> b.path("/internal/vehicles/batch").queryParam("ids", ids).build())
                .retrieve()
                .body(JsonNode.class));
    }

    public Map<String, Rider> riders(Collection<String> userIds) {
        return DownstreamCalls.batch(SERVICE, objectMapper, userIds, Rider.class, Rider::userId, ids -> restClient.get()
                .uri(b -> b.path("/internal/riders/batch").queryParam("userIds", ids).build())
                .retrieve()
                .body(JsonNode.class));
    }

    /** The Drivers Directory - filtered, sorted and paged by profile-service's driver index. */
    public IndexPage driverIndex(DriverDirectoryStatus status, String search, DriverDirectorySort sort,
                                 Sort.Direction direction, int page, int size) {
        return DownstreamCalls.data(SERVICE, objectMapper, IndexPage.class, () -> restClient.get()
                .uri(b -> {
                    b.path("/internal/drivers/index").queryParam("page", page).queryParam("size", size);
                    if (status != null) {
                        b.queryParam("status", status);
                    }
                    if (search != null && !search.isBlank()) {
                        b.queryParam("search", search);
                    }
                    if (sort != null) {
                        b.queryParam("sort", sort);
                    }
                    if (direction != null) {
                        b.queryParam("direction", direction);
                    }
                    return b.build();
                })
                .retrieve()
                .body(JsonNode.class));
    }

    public DriverDirectoryCounts driverIndexCounts(String search) {
        return DownstreamCalls.data(SERVICE, objectMapper, DriverDirectoryCounts.class, () -> restClient.get()
                .uri(b -> {
                    b.path("/internal/drivers/index/counts");
                    if (search != null && !search.isBlank()) {
                        b.queryParam("search", search);
                    }
                    return b.build();
                })
                .retrieve()
                .body(JsonNode.class));
    }

    public IndexedDriver indexedDriver(String driverId) {
        return DownstreamCalls.data(SERVICE, objectMapper, IndexedDriver.class, () -> restClient.get()
                .uri("/internal/drivers/index/{driverId}", driverId)
                .retrieve()
                .body(JsonNode.class));
    }

    /** The Document Expiry Tracker - filtered, sorted and paged by profile-service. */
    public ExpiryPage documentExpiry(DocumentExpiryTab tab, String search, DocumentExpirySort sort, Sort.Direction direction,
                                     int page, int size) {
        return DownstreamCalls.data(SERVICE, objectMapper, ExpiryPage.class, () -> restClient.get()
                .uri(b -> {
                    b.path("/internal/document-expiry").queryParam("page", page).queryParam("size", size);
                    if (tab != null) {
                        b.queryParam("tab", tab);
                    }
                    if (search != null && !search.isBlank()) {
                        b.queryParam("search", search);
                    }
                    if (sort != null) {
                        b.queryParam("sort", sort);
                    }
                    if (direction != null) {
                        b.queryParam("direction", direction);
                    }
                    return b.build();
                })
                .retrieve()
                .body(JsonNode.class));
    }

    public DocumentExpiryCounts documentExpiryCounts(String search) {
        return DownstreamCalls.data(SERVICE, objectMapper, DocumentExpiryCounts.class, () -> restClient.get()
                .uri(b -> {
                    b.path("/internal/document-expiry/counts");
                    if (search != null && !search.isBlank()) {
                        b.queryParam("search", search);
                    }
                    return b.build();
                })
                .retrieve()
                .body(JsonNode.class));
    }

    public ExpiryRow notifyDocument(String documentId, String by) {
        return DownstreamCalls.data(SERVICE, objectMapper, ExpiryRow.class, () -> restClient.post()
                .uri(b -> b.path("/internal/document-expiry/{documentId}/notify").queryParam("by", by).build(documentId))
                .retrieve()
                .body(JsonNode.class));
    }

    public DocumentExpiryNotifyResult notifyAllDocuments(DocumentExpiryTab tab, String search, String by) {
        return DownstreamCalls.data(SERVICE, objectMapper, DocumentExpiryNotifyResult.class, () -> restClient.post()
                .uri(b -> {
                    b.path("/internal/document-expiry/notify-all").queryParam("by", by);
                    if (tab != null) {
                        b.queryParam("tab", tab);
                    }
                    if (search != null && !search.isBlank()) {
                        b.queryParam("search", search);
                    }
                    return b.build();
                })
                .retrieve()
                .body(JsonNode.class));
    }

    /** The Riders Directory - filtered, sorted and paged by profile-service's rider index. */
    public RiderIndexPage riderIndex(RiderDirectoryStatus status, String search, RiderDirectorySort sort,
                                     Sort.Direction direction, int page, int size) {
        return DownstreamCalls.data(SERVICE, objectMapper, RiderIndexPage.class, () -> restClient.get()
                .uri(b -> {
                    b.path("/internal/riders/index").queryParam("page", page).queryParam("size", size);
                    if (status != null) {
                        b.queryParam("status", status);
                    }
                    if (search != null && !search.isBlank()) {
                        b.queryParam("search", search);
                    }
                    if (sort != null) {
                        b.queryParam("sort", sort);
                    }
                    if (direction != null) {
                        b.queryParam("direction", direction);
                    }
                    return b.build();
                })
                .retrieve()
                .body(JsonNode.class));
    }

    public RiderDirectoryCounts riderIndexCounts(String search) {
        return DownstreamCalls.data(SERVICE, objectMapper, RiderDirectoryCounts.class, () -> restClient.get()
                .uri(b -> {
                    b.path("/internal/riders/index/counts");
                    if (search != null && !search.isBlank()) {
                        b.queryParam("search", search);
                    }
                    return b.build();
                })
                .retrieve()
                .body(JsonNode.class));
    }

    public IndexedRider indexedRider(String riderId) {
        return DownstreamCalls.data(SERVICE, objectMapper, IndexedRider.class, () -> restClient.get()
                .uri("/internal/riders/index/{riderId}", riderId)
                .retrieve()
                .body(JsonNode.class));
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExpiryPage(List<ExpiryRow> content, int page, int size, long totalElements, int totalPages, boolean last) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExpiryRow(String documentId, String driverId, String firstName, String lastName, String phone, String photoUrl,
                            DocumentType documentType, java.time.LocalDate expiresOn, long daysLeft, DocumentExpiryStatus status,
                            VerificationStatus verificationStatus, Instant verifiedAt, Instant lastNotifiedAt, String lastNotifiedBy,
                            boolean notifiable, boolean notifiedRecently) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RiderIndexPage(List<IndexedRider> content, int page, int size, long totalElements, int totalPages, boolean last) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record IndexedRider(String riderId, String firstName, String lastName, String phone, String photoUrl, Instant joinedAt,
                               RiderDirectoryStatus status, Instant suspendedAt, String suspensionReason,
                               Long trips, Double ratingAvg, Long ratingCount, Integer reliabilityPercent, Long reportsFiled,
                               BigDecimal totalSpent) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record IndexPage(List<IndexedDriver> content, int page, int size, long totalElements, int totalPages, boolean last) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record IndexedDriver(String driverId, String firstName, String lastName, String phone, String photoUrl, Instant joinedAt,
                                DriverDirectoryStatus status, VerificationStatus verificationStatus,
                                Instant suspendedAt, String suspensionReason,
                                String vehicleMake, String vehicleModel, Integer vehicleYear, String vehicleColor, String plateNumber,
                                Long trips, Double ratingAvg, Long ratingCount, Integer reliabilityPercent, Long reportsFiled,
                                BigDecimal earningsNet, BigDecimal earningsGross) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record QueuePage(List<QueueRow> content, int page, int size, long totalElements, int totalPages, boolean last) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record QueueRow(String driverId, String firstName, String lastName, String photoUrl, VerificationQueueTab tab,
                           Instant waitingSince, List<VerificationQueueItem.Entry> items) {
    }

    private record DecisionBatch(List<ItemDecision> decisions, String reviewedBy) {
    }

    private record MarkBody(ReviewMark mark, String note, String markedBy) {
    }

    private record ReviewBody(String note, String reviewedBy) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Driver(String userId, String firstName, String lastName, String photoUrl) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Vehicle(String id, String make, String model, Integer year, String color, String plateNumber) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Rider(String userId, String firstName, String lastName, String photoUrl) {
    }
}
