package org.coryd.ops.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import java.util.function.Supplier;

/**
 * Runs one call to another service and unwraps the standard envelope to its data. Unlike the
 * enrichment clients elsewhere, nothing here degrades to empty - an ops admin approving or rejecting
 * a driver needs to see the real outcome, so a refusal comes back with the owning service's own
 * status and message, and an unreachable service is a 502, not a silent blank.
 */
@Slf4j
final class DownstreamCalls {

    private DownstreamCalls() {
    }

    static JsonNode data(String service, ObjectMapper objectMapper, Supplier<JsonNode> call) {
        try {
            JsonNode envelope = call.get();
            return envelope == null ? null : envelope.get("data");
        } catch (RestClientResponseException e) {
            throw new DownstreamException(e.getStatusCode(), messageFrom(objectMapper, e, service));
        } catch (ResourceAccessException e) {
            log.error("{} unreachable: {}", service, e.getMessage());
            throw new DownstreamException(HttpStatus.BAD_GATEWAY, service + " is unavailable right now - try again shortly");
        }
    }

    private static String messageFrom(ObjectMapper objectMapper, RestClientResponseException e, String service) {
        try {
            JsonNode body = objectMapper.readTree(e.getResponseBodyAsString());
            if (body != null && body.hasNonNull("message")) {
                return body.get("message").asText();
            }
        } catch (Exception ignored) {
            // not our envelope - fall through to the status text
        }
        return service + " returned " + e.getStatusCode().value();
    }
}
