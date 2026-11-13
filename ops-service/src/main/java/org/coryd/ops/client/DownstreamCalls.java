package org.coryd.ops.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.http.IdBatches;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Runs one call to another service and unwraps the standard envelope to its data. Unlike the
 * enrichment clients elsewhere, primary calls here never degrade to empty - an ops admin approving or
 * rejecting a driver needs to see the real outcome, so a refusal comes back with the owning service's
 * own status and message, and an unreachable service is a 502, not a silent blank. Batch lookups for
 * display details (names, phones, vehicles) are the exception: those blank out instead of failing a page.
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

    static <T> T data(String service, ObjectMapper objectMapper, Class<T> type, Supplier<JsonNode> call) {
        JsonNode node = data(service, objectMapper, call);
        try {
            return node == null || node.isNull() ? null : objectMapper.treeToValue(node, type);
        } catch (Exception e) {
            log.error("Unreadable {} response: {}", service, e.getMessage());
            throw new DownstreamException(HttpStatus.BAD_GATEWAY, service + " sent a response this console can't read");
        }
    }

    /**
     * A list-of-records lookup keyed by id - a failed batch leaves its ids without details, so missing names never sink a
     * page. Sent in batches of IdBatches.MAX_IDS: the ids go in the query string, and a long one is refused before it
     * reaches the service.
     */
    static <T> Map<String, T> batch(String service, ObjectMapper objectMapper, Collection<String> ids, Class<T> type,
                                    Function<T, String> key, Function<List<String>, JsonNode> call) {
        Map<String, T> byId = new LinkedHashMap<>();
        for (List<String> batch : IdBatches.of(ids)) {
            try {
                JsonNode items = data(service, objectMapper, () -> call.apply(batch));
                if (items != null && items.isArray()) {
                    for (JsonNode item : items) {
                        T value = objectMapper.treeToValue(item, type);
                        byId.put(key.apply(value), value);
                    }
                }
            } catch (Exception e) {
                log.warn("{} lookup for {} ids failed - showing them without details: {}", service, batch.size(), e.getMessage());
            }
        }
        return byId;
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
