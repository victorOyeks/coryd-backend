package org.coryd.ops.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.coryd.common.security.JwtUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * Phase 4 money: payment-service's /internal/ops/money and trip-service's /internal/ops/pricing, with the admin's own token -
 * payment-service checks OPS_ADMIN and signs every action with that admin. A refusal comes back with its own status and
 * message (DownstreamCalls).
 */
@Component
public class OpsMoneyClient {

    private static final String PAYMENT = "payment-service";
    private static final String TRIP = "trip-service";

    private final RestClient payment;
    private final RestClient trip;
    private final ObjectMapper objectMapper;

    public OpsMoneyClient(@Value("${services.payment.url}") String paymentServiceUrl,
                          @Value("${services.trip.url}") String tripServiceUrl,
                          @Value("${services.internal.api-key}") String internalApiKey,
                          JwtUtil jwtUtil, ObjectMapper objectMapper) {
        this.payment = OpsRestClients.build(paymentServiceUrl, internalApiKey, jwtUtil);
        this.trip = OpsRestClients.build(tripServiceUrl, internalApiKey, jwtUtil);
        this.objectMapper = objectMapper;
    }

    /** path is under /internal/ops/money; params null-free. */
    public JsonNode get(String path, MultiValueMap<String, String> params) {
        return DownstreamCalls.data(PAYMENT, objectMapper, () -> payment.get()
                .uri(b -> b.path("/internal/ops/money" + path).queryParams(params).build())
                .retrieve()
                .body(JsonNode.class));
    }

    /** The whole envelope's data and, when payment-service says something about it, its message. */
    public Result post(String path, Object body) {
        JsonNode[] envelope = new JsonNode[1];
        JsonNode data = DownstreamCalls.data(PAYMENT, objectMapper, () -> {
            envelope[0] = payment.post()
                    .uri("/internal/ops/money" + path)
                    .body(body == null ? java.util.Map.of() : body)
                    .retrieve()
                    .body(JsonNode.class);
            return envelope[0];
        });
        String message = envelope[0] != null && envelope[0].hasNonNull("message") ? envelope[0].get("message").asText() : null;
        return new Result(message, data);
    }

    public JsonNode pricing(MultiValueMap<String, String> params) {
        return DownstreamCalls.data(TRIP, objectMapper, () -> trip.get()
                .uri(b -> b.path("/internal/ops/pricing").queryParams(params).build())
                .retrieve()
                .body(JsonNode.class));
    }

    public record Result(String message, JsonNode data) {
    }
}
