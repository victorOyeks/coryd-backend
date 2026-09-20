package org.coryd.profile.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.enums.UserRole;
import org.coryd.common.http.HttpTimeouts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Dev-seeder only: resolves a seed account's real, identity-service-generated userId by
 * logging in as it (the same public POST /auth/login every real client uses), rather than
 * pre-agreeing on a fixed id across two independently-seeded services - Hibernate's
 * {@code @UuidGenerator} doesn't honor a pre-assigned id, it always generates a fresh one.
 * Retries with a short delay since identity-service may not have finished starting yet when
 * profile-service's own DataSeeder runs - systemd's restart-on-failure is the backstop if it
 * still isn't up after every attempt here.
 */
@Slf4j
@Component
public class IdentityAuthClient {

    private static final int MAX_ATTEMPTS = 15;
    private static final long RETRY_DELAY_MS = 4000;

    private final RestClient restClient;

    public IdentityAuthClient(@Value("${services.identity.url}") String identityServiceUrl) {
        this.restClient = HttpTimeouts.builder().baseUrl(identityServiceUrl).build();
    }

    public String resolveUserId(String phone, String password, UserRole role) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                LoginResponse response = restClient.post()
                        .uri("/auth/login")
                        .body(new LoginRequestBody(phone, password, role))
                        .retrieve()
                        .body(LoginResponse.class);
                return response.data().userId();
            } catch (Exception e) {
                if (attempt == MAX_ATTEMPTS) {
                    throw new IllegalStateException(
                            "Could not resolve seed userId for phone " + phone + " after " + MAX_ATTEMPTS
                                    + " attempts - is identity-service reachable at the configured services.identity.url?", e);
                }
                log.warn("Login attempt {}/{} for seed phone {} failed ({}) - retrying in {}ms",
                        attempt, MAX_ATTEMPTS, phone, e.getMessage(), RETRY_DELAY_MS);
                sleep();
            }
        }
        throw new IllegalStateException("unreachable");
    }

    private void sleep() {
        try {
            Thread.sleep(RETRY_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to retry identity-service login", e);
        }
    }

    private record LoginRequestBody(String phone, String password, UserRole role) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record LoginResponse(Data data) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        private record Data(String userId) {
        }
    }
}
