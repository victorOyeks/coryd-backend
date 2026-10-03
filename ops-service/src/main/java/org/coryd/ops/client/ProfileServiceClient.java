package org.coryd.ops.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.coryd.common.http.HttpTimeouts;
import org.coryd.common.security.JwtUtil;
import org.coryd.common.security.client.ForwardedAuthInterceptor;
import org.coryd.common.security.client.InternalApiKeyInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

/** Driver verification lives in profile-service. Calls carry the ops admin's own token, so
 * profile-service's OPS_ADMIN checks still decide who may approve - this service adds no second gate. */
@Component
public class ProfileServiceClient {

    private static final String SERVICE = "profile-service";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public ProfileServiceClient(@Value("${services.profile.url}") String profileServiceUrl,
                                @Value("${services.internal.api-key}") String internalApiKey,
                                JwtUtil jwtUtil, ObjectMapper objectMapper) {
        // The JDK client explicitly: the default this Boot version picks is HttpURLConnection, which
        // cannot send PATCH - the decision call would fail as "unreachable".
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(HttpTimeouts.DEFAULT_CONNECT_TIMEOUT)
                .build());
        requestFactory.setReadTimeout(HttpTimeouts.DEFAULT_READ_TIMEOUT);
        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .baseUrl(profileServiceUrl)
                .requestInterceptor(new ForwardedAuthInterceptor(jwtUtil))
                .requestInterceptor(new InternalApiKeyInterceptor(internalApiKey))
                .build();
        this.objectMapper = objectMapper;
    }

    public JsonNode pendingVerifications() {
        return DownstreamCalls.data(SERVICE, objectMapper, () -> restClient.get()
                .uri("/drivers/verification/pending")
                .retrieve()
                .body(JsonNode.class));
    }

    public JsonNode verificationStatus(String driverId) {
        return DownstreamCalls.data(SERVICE, objectMapper, () -> restClient.get()
                .uri("/drivers/{driverId}/verification-status", driverId)
                .retrieve()
                .body(JsonNode.class));
    }

    public JsonNode decideVerification(String driverId, JsonNode decisionBatch) {
        return DownstreamCalls.data(SERVICE, objectMapper, () -> restClient.patch()
                .uri("/drivers/{driverId}/verification", driverId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(decisionBatch)
                .retrieve()
                .body(JsonNode.class));
    }
}
