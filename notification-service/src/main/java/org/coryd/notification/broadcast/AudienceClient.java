package org.coryd.notification.broadcast;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.coryd.common.http.HttpTimeouts;
import org.coryd.common.security.JwtUtil;
import org.coryd.common.security.client.InternalApiKeyInterceptor;
import org.coryd.common.security.client.SystemAuthInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;

import java.util.List;

/** profile-service's broadcast audiences - with a system token (the sender job runs with no admin behind it). */
@Component
public class AudienceClient {

    private final RestClient profile;

    public AudienceClient(@Value("${services.profile.url}") String profileUrl,
                          @Value("${services.internal.api-key}") String internalApiKey, JwtUtil jwtUtil) {
        this.profile = HttpTimeouts.builder().baseUrl(profileUrl)
                .requestInterceptor(new SystemAuthInterceptor(jwtUtil))
                .requestInterceptor(new InternalApiKeyInterceptor(internalApiKey))
                .build();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Member(String userId, String role, String ref, String name, String phone, String email) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Page(List<Member> members, String nextAfter) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Count(long total, long withPhone, long withEmail, List<String> unmatched) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Envelope<T>(T data) {
    }

    public Page page(Audience audience, String after, int size) {
        Envelope<Page> e = profile.get().uri(b -> query(b.path("/internal/ops/audience"), audience).queryParam("size", size)
                        .queryParamIfPresent("after", java.util.Optional.ofNullable(after)).build())
                .retrieve().body(new org.springframework.core.ParameterizedTypeReference<Envelope<Page>>() {
                });
        return e == null || e.data() == null ? new Page(List.of(), null) : e.data();
    }

    public Count count(Audience audience) {
        Envelope<Count> e = profile.get().uri(b -> query(b.path("/internal/ops/audience/count"), audience).build())
                .retrieve().body(new org.springframework.core.ParameterizedTypeReference<Envelope<Count>>() {
                });
        return e == null || e.data() == null ? new Count(0, 0, 0, List.of()) : e.data();
    }

    private static UriBuilder query(UriBuilder b, Audience audience) {
        b.queryParam("type", audience.type().name());
        if (audience.activeDays() != null) {
            b.queryParam("activeDays", audience.activeDays());
        }
        if (audience.ids() != null && !audience.ids().isEmpty()) {
            b.queryParam("ids", audience.ids().toArray());
        }
        if (audience.refs() != null && !audience.refs().isEmpty()) {
            b.queryParam("refs", audience.refs().toArray());
        }
        return b;
    }

    /** Who a broadcast is for, as sent to profile-service. */
    public record Audience(AudienceType type, Integer activeDays, List<String> ids, List<String> refs) {
    }

}
