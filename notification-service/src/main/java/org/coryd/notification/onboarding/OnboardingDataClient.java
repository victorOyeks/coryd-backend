package org.coryd.notification.onboarding;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.coryd.common.enums.OnboardingStep;
import org.coryd.common.http.HttpTimeouts;
import org.coryd.common.security.JwtUtil;
import org.coryd.common.security.client.InternalApiKeyInterceptor;
import org.coryd.common.security.client.SystemAuthInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What the daily jobs read from the services that own it, with a system token (no admin behind a scheduled run): from
 * identity-service the accounts to consider and the ops team; from profile-service where each person stands in signing
 * up and who is waiting on an officer. A call that fails throws - a run must not act on half the picture.
 */
@Component
public class OnboardingDataClient {

    private final RestClient identity;
    private final RestClient profile;

    public OnboardingDataClient(@Value("${services.identity.url}") String identityUrl, @Value("${services.profile.url}") String profileUrl,
                                @Value("${services.internal.api-key}") String internalApiKey, JwtUtil jwtUtil) {
        this.identity = build(identityUrl, internalApiKey, jwtUtil);
        this.profile = build(profileUrl, internalApiKey, jwtUtil);
    }

    private static RestClient build(String url, String key, JwtUtil jwtUtil) {
        return HttpTimeouts.builder().baseUrl(url)
                .requestInterceptor(new SystemAuthInterceptor(jwtUtil))
                .requestInterceptor(new InternalApiKeyInterceptor(key))
                .build();
    }

    // ---- identity-service ----------------------------------------------------------------------------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Candidate(String userId, String firstName, List<String> roles, Instant signedUpAt, Instant termsAcceptedAt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CandidatePage(List<Candidate> people, String nextAfter) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OpsAdmin(String userId, String fullName, String email, String opsRole, String status) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Envelope<T>(T data) {
    }

    public CandidatePage candidates(String after, int size) {
        Envelope<CandidatePage> e = identity.get().uri(b -> b.path("/internal/onboarding/candidates").queryParam("size", size)
                        .queryParamIfPresent("after", Optional.ofNullable(after)).build())
                .retrieve().body(new ParameterizedTypeReference<Envelope<CandidatePage>>() {
                });
        return e == null || e.data() == null ? new CandidatePage(List.of(), null) : e.data();
    }

    public List<OpsAdmin> opsAdmins() {
        Envelope<List<OpsAdmin>> e = identity.get().uri("/internal/ops-admins")
                .retrieve().body(new ParameterizedTypeReference<Envelope<List<OpsAdmin>>>() {
                });
        return e == null || e.data() == null ? List.of() : e.data();
    }

    // ---- profile-service -----------------------------------------------------------------------------------------

    public record Person(String userId, List<String> roles) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Step(OnboardingStep step, OnboardingStep.Reason reason) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RoleStatus(String role, boolean complete, List<Step> steps, boolean waitingOnOps, Instant lastProgressAt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Status(String userId, List<RoleStatus> roles) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record WaitingItem(String kind, String userId, String role, String ref, String name, String what, Instant submittedAt,
                              Instant waitingSince) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Waiting(long drivers, long identityChecks, long partialNameMatches, List<WaitingItem> items) {
    }

    public List<Status> status(List<Person> people) {
        if (people.isEmpty()) {
            return List.of();
        }
        Envelope<List<Status>> e = profile.post().uri("/internal/onboarding/status").body(Map.of("people", people))
                .retrieve().body(new ParameterizedTypeReference<Envelope<List<Status>>>() {
                });
        return e == null || e.data() == null ? List.of() : e.data();
    }

    public Waiting verificationWaiting() {
        Envelope<Waiting> e = profile.get().uri("/internal/onboarding/verification-waiting")
                .retrieve().body(new ParameterizedTypeReference<Envelope<Waiting>>() {
                });
        return e == null || e.data() == null ? new Waiting(0, 0, 0, List.of()) : e.data();
    }
}
