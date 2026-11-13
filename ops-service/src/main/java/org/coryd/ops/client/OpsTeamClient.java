package org.coryd.ops.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.coryd.common.security.JwtUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/** The admin team lives in identity-service (/internal/ops-admins) - refusals come back with its own status and message. */
@Component
public class OpsTeamClient {

    private static final String SERVICE = "identity-service";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public OpsTeamClient(@Value("${services.identity.url}") String identityServiceUrl,
                         @Value("${services.internal.api-key}") String internalApiKey,
                         JwtUtil jwtUtil, ObjectMapper objectMapper) {
        this.restClient = OpsRestClients.build(identityServiceUrl, internalApiKey, jwtUtil);
        this.objectMapper = objectMapper;
    }

    public JsonNode list() {
        return DownstreamCalls.data(SERVICE, objectMapper, () -> restClient.get().uri("/internal/ops-admins").retrieve().body(JsonNode.class));
    }

    public JsonNode get(String userId) {
        return DownstreamCalls.data(SERVICE, objectMapper, () -> restClient.get().uri("/internal/ops-admins/{id}", userId)
                .retrieve().body(JsonNode.class));
    }

    public void seen(String userId) {
        DownstreamCalls.data(SERVICE, objectMapper, () -> restClient.post().uri("/internal/ops-admins/{id}/seen", userId)
                .retrieve().body(JsonNode.class));
    }

    public JsonNode invite(Map<String, Object> body) {
        return post("/internal/ops-admins/invites", body);
    }

    /** The admin's two-factor state and the console's sign-in rules (mfaRequired, session minutes). */
    public JsonNode mfaStatus(String userId) {
        return DownstreamCalls.data(SERVICE, objectMapper, () -> restClient.get().uri("/internal/ops-admins/{id}/mfa", userId)
                .retrieve().body(JsonNode.class));
    }

    /** action: setup, enable, disable, recovery-codes, reset - for the signed-in admin, or (reset) the admin a super admin picked. */
    public JsonNode mfa(String userId, String action, Map<String, Object> body) {
        return post("/internal/ops-admins/" + userId + "/mfa/" + action, body == null ? Map.of() : body);
    }

    /** action: invite/resend, invite/revoke, role, deactivate, reactivate. */
    public JsonNode act(String userId, String action, Map<String, Object> body) {
        return post("/internal/ops-admins/" + userId + "/" + action, body);
    }

    private JsonNode post(String path, Map<String, Object> body) {
        return DownstreamCalls.data(SERVICE, objectMapper, () -> restClient.post().uri(path)
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(JsonNode.class));
    }
}
