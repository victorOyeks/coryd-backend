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
        return DownstreamCalls.data(SERVICE, objectMapper, QueuePage.class, () -> restClient.get()
                .uri(b -> {
                    b.path("/drivers/verification/queue").queryParam("page", page).queryParam("size", size);
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
