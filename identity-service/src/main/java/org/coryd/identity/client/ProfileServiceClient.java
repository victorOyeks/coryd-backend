package org.coryd.identity.client;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.enums.UserRole;
import org.coryd.common.http.HttpTimeouts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Self-lookup only: forwards the caller's own bearer token to profile-service
 * so "does my profile exist" is answered as that same authenticated user, not
 * as an anonymous service-to-service call (which profile-service's security
 * config would reject outright).
 */
@Slf4j
@Component
public class ProfileServiceClient {

    private final RestClient restClient;

    public ProfileServiceClient(@Value("${services.profile.url}") String profileServiceUrl) {
        this.restClient = HttpTimeouts.create(profileServiceUrl);
    }

    /** Whether the profile for this role exists, and where the person's NIN + selfie check stands (PENDING until submitted). */
    public record ProfileStatus(boolean exists, String identityVerificationStatus) {
        public boolean identityVerified() {
            return "APPROVED".equals(identityVerificationStatus);
        }
    }

    public ProfileStatus profileStatus(UserRole activeRole, String bearerToken) {
        return new ProfileStatus(hasProfile(activeRole, bearerToken), identityStatus(bearerToken));
    }

    private boolean hasProfile(UserRole activeRole, String bearerToken) {
        String path = activeRole == UserRole.DRIVER ? "/drivers/me" : "/riders/me";
        try {
            restClient.get()
                    .uri(path)
                    .header(HttpHeaders.AUTHORIZATION, bearerToken)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode() != HttpStatusCode.valueOf(404)) {
                log.warn("Unexpected response checking profile completeness: {}", e.getMessage());
            }
            return false;
        } catch (Exception e) {
            log.warn("Could not reach profile-service to check profile completeness: {}", e.getMessage());
            return false;
        }
    }

    /** PENDING when it can't be read - the app then shows the NIN step, which is the safe side. */
    private String identityStatus(String bearerToken) {
        try {
            JsonNode body = restClient.get()
                    .uri("/identity-verification/me")
                    .header(HttpHeaders.AUTHORIZATION, bearerToken)
                    .retrieve()
                    .body(JsonNode.class);
            String status = body == null ? null : body.path("data").path("status").asText(null);
            return status == null ? "PENDING" : status;
        } catch (Exception e) {
            log.warn("Could not read the identity check from profile-service: {}", e.getMessage());
            return "PENDING";
        }
    }
}
