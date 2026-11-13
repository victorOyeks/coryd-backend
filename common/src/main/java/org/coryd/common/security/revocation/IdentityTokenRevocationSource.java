package org.coryd.common.security.revocation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.coryd.common.http.HttpTimeouts;
import org.coryd.common.kafka.events.UserTokensRevokedEvent;
import org.coryd.common.security.JwtUtil;
import org.coryd.common.security.client.InternalApiKeyInterceptor;
import org.coryd.common.security.client.SystemAuthInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * The snapshot from identity-service: {@code GET /internal/users/token-revocations}, with the internal key and a system
 * token. identity-service itself turns this off (coryd.token-revocation.http-bootstrap.enabled=false) and reads its own
 * database instead.
 */
@Component
@ConditionalOnProperty(name = "coryd.token-revocation.http-bootstrap.enabled", havingValue = "true", matchIfMissing = true)
public class IdentityTokenRevocationSource implements TokenRevocationSource {

    private final RestClient restClient;

    public IdentityTokenRevocationSource(
            @Value("${coryd.token-revocation.identity-url:${services.identity.url:${IDENTITY_SERVICE_URL:http://localhost:9101/identity}}}") String identityUrl,
            @Value("${services.internal.api-key:${INTERNAL_API_KEY:}}") String internalApiKey,
            JwtUtil jwtUtil) {
        this.restClient = HttpTimeouts.builder()
                .baseUrl(identityUrl)
                .requestInterceptor(new SystemAuthInterceptor(jwtUtil))
                .requestInterceptor(new InternalApiKeyInterceptor(internalApiKey))
                .build();
    }

    @Override
    public List<UserTokensRevokedEvent> fetchAll() {
        Envelope envelope = restClient.get().uri("/internal/users/token-revocations").retrieve()
                .body(new ParameterizedTypeReference<Envelope>() {
                });
        return envelope == null || envelope.data() == null ? List.of() : envelope.data();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Envelope(List<UserTokensRevokedEvent> data) {
    }
}
