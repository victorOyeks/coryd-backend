package org.coryd.ops.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.coryd.common.enums.AccountActionType;
import org.coryd.common.enums.AccountDeletionStatus;
import org.coryd.common.security.JwtUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
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

    public void suspend(String userId, String reason, String opsUserId, String opsUserName) {
        accountAction(userId, "suspend", reason, opsUserId, opsUserName);
    }

    /** Deletes the account everywhere - refused (400, with the reasons) while the person is on a trip or has money still moving. */
    public void deleteAccount(String userId, String opsUserId) {
        DownstreamCalls.data(SERVICE, objectMapper, () -> restClient.delete()
                .uri(b -> b.path("/internal/users/{userId}").queryParam("opsUserId", opsUserId).build(userId))
                .retrieve()
                .body(JsonNode.class));
    }

    public void reinstate(String userId, String reason, String opsUserId, String opsUserName) {
        accountAction(userId, "reinstate", reason, opsUserId, opsUserName);
    }

    public void signOutEverywhere(String userId, String reason, String opsUserId, String opsUserName) {
        accountAction(userId, "sign-out-everywhere", reason, opsUserId, opsUserName);
    }

    private void accountAction(String userId, String action, String reason, String opsUserId, String opsUserName) {
        Map<String, Object> body = new HashMap<>();
        body.put("reason", reason);
        body.put("opsUserId", opsUserId);
        body.put("opsUserName", opsUserName);
        DownstreamCalls.data(SERVICE, objectMapper, () -> restClient.post()
                .uri("/internal/users/{userId}/{action}", userId, action)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class));
    }

    /** Status, roles, devices, suspension and its history - the account half of a profile. */
    public JsonNode account(String userId) {
        return DownstreamCalls.data(SERVICE, objectMapper, () -> restClient.get()
                .uri("/internal/users/{userId}/account", userId)
                .retrieve()
                .body(JsonNode.class));
    }

    public JsonNode accountHistory(String userId, int page, int size) {
        return DownstreamCalls.data(SERVICE, objectMapper, () -> restClient.get()
                .uri(b -> b.path("/internal/users/{userId}/account-actions").queryParam("page", page).queryParam("size", size).build(userId))
                .retrieve()
                .body(JsonNode.class));
    }

    /** Adds an edit or reset made in another service to the account history. changes: [{field, before, after}]. */
    public JsonNode recordAccountAction(String userId, AccountActionType action, String reason, JsonNode changes,
                                        String opsUserId, String opsUserName) {
        Map<String, Object> body = new HashMap<>();
        body.put("action", action);
        body.put("reason", reason);
        body.put("changes", changes);
        body.put("opsUserId", opsUserId);
        body.put("opsUserName", opsUserName);
        return DownstreamCalls.data(SERVICE, objectMapper, () -> restClient.post()
                .uri("/internal/users/{userId}/account-actions", userId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class));
    }

    public JsonNode accountDeletions(AccountDeletionStatus status, int page, int size) {
        return DownstreamCalls.data(SERVICE, objectMapper, () -> restClient.get()
                .uri(b -> {
                    b.path("/internal/account-deletions").queryParam("page", page).queryParam("size", size);
                    if (status != null) {
                        b.queryParam("status", status);
                    }
                    return b.build();
                })
                .retrieve()
                .body(JsonNode.class));
    }

    public JsonNode accountDeletion(String id) {
        return DownstreamCalls.data(SERVICE, objectMapper, () -> restClient.get()
                .uri("/internal/account-deletions/{id}", id)
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
