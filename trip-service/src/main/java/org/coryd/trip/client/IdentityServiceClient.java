package org.coryd.trip.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.security.ForwardedAuthInterceptor;
import org.coryd.common.security.InternalApiKeyInterceptor;
import org.coryd.common.http.HttpTimeouts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Read-only contact lookup against identity-service (driver phone, for the Call button) - degrades to null on failure, same as ProfileServiceClient. */
@Slf4j
@Component
public class IdentityServiceClient {

    private final RestClient restClient;

    public IdentityServiceClient(@Value("${services.identity.url}") String identityServiceUrl,
                                  @Value("${services.internal.api-key}") String internalApiKey) {
        this.restClient = HttpTimeouts.builder()
                .baseUrl(identityServiceUrl)
                .requestInterceptor(new ForwardedAuthInterceptor())
                .requestInterceptor(new InternalApiKeyInterceptor(internalApiKey))
                .build();
    }

    public String fetchPhone(String userId) {
        try {
            return restClient.get()
                    .uri("/internal/users/{userId}/contact", userId)
                    .retrieve()
                    .body(UserContactView.class)
                    .data().phone();
        } catch (Exception e) {
            log.warn("Could not fetch phone for user {}: {}", userId, e.getMessage());
            return null;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record UserContactView(Data data) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        private record Data(String phone) {
        }
    }
}
