package org.coryd.ops.privacy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.coryd.common.util.PiiMask;

import java.util.ArrayList;
import java.util.List;

/**
 * Defence in depth for responses ops-service passes through from profile-service as JSON: profile-service already masks
 * them for ops, but should a full value ever come back (an older profile-service mid-deploy), it is masked here rather
 * than shown. licenceNumber -> licenceNumberMasked, dateOfBirth -> birthYear, nin/ninLast4 -> ninMasked (ninLast4 kept),
 * idNumber removed. See docs/ops-console/privacy.md.
 */
public final class PiiRedactor {

    private PiiRedactor() {
    }

    public static JsonNode redact(JsonNode node) {
        if (node instanceof ObjectNode object) {
            List<String> names = new ArrayList<>();
            object.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                JsonNode value = object.get(name);
                switch (name) {
                    case "licenceNumber" -> {
                        object.remove(name);
                        if (!object.has("licenceNumberMasked") && value != null && value.isTextual()) {
                            object.put("licenceNumberMasked", PiiMask.trailing(value.asText()));
                        }
                    }
                    case "dateOfBirth" -> {
                        object.remove(name);
                        if (!object.has("birthYear") && value != null && value.isTextual()) {
                            object.put("birthYear", PiiMask.birthYear(value.asText()));
                        }
                    }
                    case "idNumber", "nin" -> object.remove(name);
                    case "ninLast4" -> {
                        if (!object.has("ninMasked") && value != null && value.isTextual()) {
                            object.put("ninMasked", PiiMask.nin(value.asText()));
                        }
                    }
                    default -> redact(value);
                }
            }
        } else if (node instanceof ArrayNode array) {
            array.forEach(PiiRedactor::redact);
        }
        return node;
    }
}
