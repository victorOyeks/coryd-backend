package org.coryd.profile.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.security.client.ForwardedAuthInterceptor;
import org.coryd.common.security.client.InternalApiKeyInterceptor;
import org.coryd.common.http.HttpTimeouts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Read-only contact lookup against identity-service (phone, for the driver/rider's own Profile
 * screen - neither DriverProfile nor RiderProfile stores it) - degrades to null on failure rather
 * than blocking the profile screen from loading. */
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

    /**
     * Mirrors the display name onto the identity User so it can ride in the JWT and the auth
     * response - signup only captures a phone, so identity has no name of its own. Best-effort:
     * a failure here must not fail the profile save.
     */
    public void syncName(String userId, String firstName, String lastName) {
        if (firstName == null || firstName.isBlank()) {
            return;
        }
        try {
            java.util.Map<String, String> body = new java.util.HashMap<>();
            body.put("firstName", firstName);
            body.put("lastName", lastName);
            restClient.put()
                    .uri("/internal/users/{userId}/name", userId)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.warn("Could not sync name for user {}: {}", userId, e.getMessage());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record UserContactView(Data data) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        private record Data(String phone) {
        }
    }
}
