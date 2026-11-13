package org.coryd.ops.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.coryd.common.exception.BadRequestException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Where the unified audit feed got to in each source: the last entry taken from it (time + its own id) and which sources
 * have nothing more. Opaque to the console - base64url JSON, sent back as ?cursor= for the next page.
 */
record AuditCursor(Map<String, Position> positions, Set<String> exhausted) {

    record Position(Instant at, String id) {
    }

    static AuditCursor start() {
        return new AuditCursor(new LinkedHashMap<>(), new LinkedHashSet<>());
    }

    static AuditCursor decode(String cursor, ObjectMapper mapper) {
        if (cursor == null || cursor.isBlank()) {
            return start();
        }
        try {
            JsonNode json = mapper.readTree(Base64.getUrlDecoder().decode(cursor.trim()));
            AuditCursor c = start();
            json.path("p").fields().forEachRemaining(e -> c.positions.put(e.getKey(),
                    new Position(Instant.parse(e.getValue().path("at").asText()), e.getValue().path("id").asText())));
            json.path("d").forEach(d -> c.exhausted.add(d.asText()));
            return c;
        } catch (Exception e) {
            throw new BadRequestException("Invalid cursor - start again from the first page");
        }
    }

    String encode(ObjectMapper mapper) {
        ObjectNode json = mapper.createObjectNode();
        ObjectNode p = json.putObject("p");
        positions.forEach((source, pos) -> p.putObject(source).put("at", pos.at().toString()).put("id", pos.id()));
        exhausted.forEach(json.putArray("d")::add);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.toString().getBytes(StandardCharsets.UTF_8));
    }
}
