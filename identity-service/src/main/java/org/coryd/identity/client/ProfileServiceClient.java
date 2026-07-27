package org.coryd.identity.client;

import lombok.extern.slf4j.Slf4j;
import org.coryd.common.enums.UserRole;
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
        this.restClient = RestClient.create(profileServiceUrl);
    }

    public boolean hasProfile(UserRole activeRole, String bearerToken) {
        String path = activeRole == UserRole.DRIVER ? "/drivers/me" : "/riders/me";
        try {
            restClient.get()
                    .uri(path)
                    .header(HttpHeaders.AUTHORIZATION, bearerToken)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode() == HttpStatusCode.valueOf(404)) {
                return false;
            }
            log.warn("Unexpected response checking profile completeness: {}", e.getMessage());
            return false;
        } catch (Exception e) {
            log.warn("Could not reach profile-service to check profile completeness: {}", e.getMessage());
            return false;
        }
    }
}
