package org.coryd.common.settings;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.coryd.common.http.HttpTimeouts;
import org.coryd.common.kafka.events.PlatformSettingChangedEvent;
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
 * The snapshot from trip-service: {@code GET /internal/settings}, with the internal key and a system token. trip-service
 * turns this off (coryd.platform-settings.http-bootstrap.enabled=false) and reads its own table instead.
 */
@Component
@ConditionalOnProperty(name = "coryd.platform-settings.http-bootstrap.enabled", havingValue = "true", matchIfMissing = true)
public class TripPlatformSettingsSource implements PlatformSettingsSource {

    private final RestClient restClient;

    public TripPlatformSettingsSource(
            @Value("${coryd.platform-settings.trip-url:${services.trip.url:${TRIP_SERVICE_URL:http://localhost:9103/trip}}}") String tripUrl,
            @Value("${services.internal.api-key:${INTERNAL_API_KEY:}}") String internalApiKey,
            JwtUtil jwtUtil) {
        this.restClient = HttpTimeouts.builder()
                .baseUrl(tripUrl)
                .requestInterceptor(new SystemAuthInterceptor(jwtUtil))
                .requestInterceptor(new InternalApiKeyInterceptor(internalApiKey))
                .build();
    }

    @Override
    public List<PlatformSettingChangedEvent> fetchAll() {
        Envelope envelope = restClient.get().uri("/internal/settings").retrieve()
                .body(new ParameterizedTypeReference<Envelope>() {
                });
        return envelope == null || envelope.data() == null ? List.of() : envelope.data();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Envelope(List<PlatformSettingChangedEvent> data) {
    }
}
