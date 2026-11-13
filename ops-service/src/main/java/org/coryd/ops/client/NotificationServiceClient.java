package org.coryd.ops.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.security.JwtUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/** Delivery records in notification-service - which channel an alert actually went out on. Display only: null when unreachable. */
@Slf4j
@Component
public class NotificationServiceClient {

    private static final String SERVICE = "notification-service";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public NotificationServiceClient(@Value("${services.notification.url}") String notificationServiceUrl,
                                     @Value("${services.internal.api-key}") String internalApiKey,
                                     JwtUtil jwtUtil, ObjectMapper objectMapper) {
        this.restClient = OpsRestClients.build(notificationServiceUrl, internalApiKey, jwtUtil);
        this.objectMapper = objectMapper;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Delivery(Instant at, String eventType, String channel, String recipientPhone, String recipientEmail, String userId,
                           boolean delivered, String errorCode) {
    }

    /** null when notification-service can't be reached - the page still loads, the channels show as unknown. */
    public List<Delivery> deliveries(Collection<String> eventTypes, Instant from, Instant to) {
        try {
            Delivery[] rows = DownstreamCalls.data(SERVICE, objectMapper, Delivery[].class, () -> restClient.get()
                    .uri(b -> b.path("/internal/ops/deliveries").queryParam("eventTypes", eventTypes.toArray())
                            .queryParam("from", from.toString()).queryParam("to", to.toString()).build())
                    .retrieve()
                    .body(JsonNode.class));
            return rows == null ? List.of() : Arrays.asList(rows);
        } catch (Exception e) {
            log.warn("Delivery records unavailable: {}", e.getMessage());
            return null;
        }
    }
}
