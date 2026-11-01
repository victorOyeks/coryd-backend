package org.coryd.ops.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.coryd.common.enums.VerificationQueueTab;
import org.coryd.common.security.JwtUtil;
import org.coryd.ops.dto.ItemDecision;
import org.coryd.ops.dto.VerificationQueueCountsResponse;
import org.coryd.ops.dto.VerificationQueueItem;
import org.coryd.ops.dto.VerificationStatusResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    public org.coryd.ops.dto.VerificationReview markVerificationItem(String driverId, org.coryd.common.enums.ReviewableItem item,
                                                                    org.coryd.common.enums.ReviewMark mark, String note, String markedBy) {
        return DownstreamCalls.data(SERVICE, objectMapper, org.coryd.ops.dto.VerificationReview.class, () -> restClient.put()
                .uri("/drivers/{driverId}/verification/items/{item}/review", driverId, item)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new MarkBody(mark, note, markedBy))
                .retrieve()
                .body(JsonNode.class));
    }

    public org.coryd.ops.dto.VerificationReview resetVerificationItem(String driverId, org.coryd.common.enums.ReviewableItem item) {
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

    public List<org.coryd.ops.dto.VerificationDecisionHistoryItem> verificationHistory(String driverId) {
        org.coryd.ops.dto.VerificationDecisionHistoryItem[] items = DownstreamCalls.data(SERVICE, objectMapper,
                org.coryd.ops.dto.VerificationDecisionHistoryItem[].class, () -> restClient.get()
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record QueuePage(List<QueueRow> content, int page, int size, long totalElements, int totalPages, boolean last) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record QueueRow(String driverId, String firstName, String lastName, String photoUrl, VerificationQueueTab tab,
                           Instant waitingSince, List<VerificationQueueItem.Entry> items) {
    }

    private record DecisionBatch(List<ItemDecision> decisions, String reviewedBy) {
    }

    private record MarkBody(org.coryd.common.enums.ReviewMark mark, String note, String markedBy) {
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
