package org.coryd.ops.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.coryd.common.security.JwtUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;

/** Phone numbers and account status (suspension) live only in identity-service. */
@Component
public class IdentityServiceClient {

    private static final String SERVICE = "identity-service";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public IdentityServiceClient(@Value("${services.identity.url}") String identityServiceUrl,
                                 @Value("${services.internal.api-key}") String internalApiKey,
                                 JwtUtil jwtUtil, ObjectMapper objectMapper) {
        this.restClient = OpsRestClients.build(identityServiceUrl, internalApiKey, jwtUtil);
        this.objectMapper = objectMapper;
    }

    public Map<String, Contact> contacts(Collection<String> userIds) {
        return DownstreamCalls.batch(SERVICE, objectMapper, userIds, Contact.class, Contact::userId, ids -> restClient.get()
                .uri(b -> b.path("/internal/users/contacts").queryParam("userIds", ids).build())
                .retrieve()
                .body(JsonNode.class));
    }

    public JsonNode heardAboutUs(Instant from, Instant to) {
        return DownstreamCalls.data(SERVICE, objectMapper, () -> restClient.get()
                .uri(b -> b.path("/internal/stats/heard-about-us").queryParam("from", from).queryParam("to", to).build())
                .retrieve()
                .body(JsonNode.class));
    }

    /** The website's beta waitlist - passed through as identity-service shapes it. */
    public JsonNode betaSignups(String status, String willMostly, String device, String search, int page, int size) {
        return DownstreamCalls.data(SERVICE, objectMapper, () -> restClient.get()
                .uri(b -> {
                    b.path("/internal/beta-signups").queryParam("page", page).queryParam("size", size);
                    if (status != null) {
                        b.queryParam("status", status);
                    }
                    if (willMostly != null) {
                        b.queryParam("willMostly", willMostly);
                    }
                    if (device != null) {
                        b.queryParam("device", device);
                    }
                    if (search != null && !search.isBlank()) {
                        b.queryParam("search", search);
                    }
                    return b.build();
                })
                .retrieve()
                .body(JsonNode.class));
    }

    public JsonNode betaSignupSummary() {
        return DownstreamCalls.data(SERVICE, objectMapper, () -> restClient.get().uri("/internal/beta-signups/summary")
                .retrieve().body(JsonNode.class));
    }

    public JsonNode markBetaSignupInvited(String id, String opsUserId) {
        return DownstreamCalls.data(SERVICE, objectMapper, () -> restClient.post()
                .uri(b -> b.path("/internal/beta-signups/{id}/invited").queryParam("opsUserId", opsUserId).build(id))
                .retrieve().body(JsonNode.class));
    }

    public void suspend(String userId, String reason, String opsUserId) {
        DownstreamCalls.data(SERVICE, objectMapper, () -> restClient.post()
                .uri("/internal/users/{userId}/suspend", userId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("reason", reason, "opsUserId", opsUserId))
                .retrieve()
                .body(JsonNode.class));
    }

    /** Deletes the account everywhere - refused (400, with the reasons) while the person is on a trip or has money still moving. */
    public void deleteAccount(String userId, String opsUserId) {
        DownstreamCalls.data(SERVICE, objectMapper, () -> restClient.delete()
                .uri(b -> b.path("/internal/users/{userId}").queryParam("opsUserId", opsUserId).build(userId))
                .retrieve()
                .body(JsonNode.class));
    }

    public void reinstate(String userId, String opsUserId) {
        DownstreamCalls.data(SERVICE, objectMapper, () -> restClient.post()
                .uri(b -> b.path("/internal/users/{userId}/reinstate").queryParam("opsUserId", opsUserId).build(userId))
                .retrieve()
                .body(JsonNode.class));
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    /** suspendedAt / suspensionReason are set while the account is suspended; joinedAt is when they signed up. */
    public record Contact(String userId, String fullName, String phone, Instant suspendedAt, String suspensionReason,
                          Instant joinedAt) {

        public Contact(String userId, String fullName, String phone) {
            this(userId, fullName, phone, null, null, null);
        }
    }
}
