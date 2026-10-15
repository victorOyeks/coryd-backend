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

    public void suspend(String userId, String reason, String opsUserId) {
        DownstreamCalls.data(SERVICE, objectMapper, () -> restClient.post()
                .uri("/internal/users/{userId}/suspend", userId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("reason", reason, "opsUserId", opsUserId))
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
    public record Contact(String userId, String fullName, String phone) {
    }
}
