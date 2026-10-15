package org.coryd.profile.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.http.HttpTimeouts;
import org.coryd.profile.config.PremblyProperties;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Thin transport over Prembly's verification endpoints. Every failure - timeout, rate limit, a
 * plate or licence Prembly has never heard of - comes back as a null payload rather than an
 * exception, because the caller's answer to all of them is the same: leave the item pending for
 * a human. Nothing here decides anything.
 */
@Slf4j
@Component
public class PremblyClient {

    /** FRSC sits behind Prembly and is routinely slower than a service-to-service hop. */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final PremblyProperties properties;

    public PremblyClient(PremblyProperties properties, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        // The secret key is the whole of Prembly's auth - they are explicit that no other
        // authorization header should be sent.
        this.restClient = HttpTimeouts.builder(HttpTimeouts.DEFAULT_CONNECT_TIMEOUT, READ_TIMEOUT)
                .baseUrl(properties.getBaseUrl())
                .defaultHeader("x-api-key", properties.getApiKey() == null ? "" : properties.getApiKey())
                .defaultHeader("accept", "application/json")
                .build();
    }

    /**
     * FRSC driver's licence, advance/v2 - the basic endpoint only answers verified true/false,
     * which gives us nothing to show ops when it says no. v2 returns the names on the record so a
     * near-miss can be judged instead of guessed at.
     */
    public DriversLicenceResult verifyDriversLicence(String licenceNumber, String firstName, String lastName) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("number", licenceNumber);
        fields.put("first_name", firstName);
        fields.put("last_name", lastName);
        JsonNode body = post("/verification/drivers_license/advance/v2", fields, "driver's licence " + licenceNumber);
        DriversLicenceResult result = read(body, DriversLicenceResult.class);
        return result == null ? null : result.withRawResponse(keepable(body));
    }

    /**
     * NIN Basic: NIMC's record for the number - names, no photo. The names come back both under "data"
     * and "nin_data"; either is read.
     */
    public NinResult verifyNinBasic(String nin) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("number", nin);
        JsonNode body = post(properties.getNinBasicPath(), fields, "NIN ending " + last4(nin));
        return body == null ? null : NinResult.from(body, keepable(body));
    }

    /**
     * NIN with face: the record, and whether the selfie is the face on it. Prembly applies the
     * threshold we send and answers in face_data.status - its "confidence" isn't on a documented scale
     * (its own sample shows 1 for a match), so it's recorded, not compared.
     */
    public NinResult verifyNinWithFace(String nin, String selfieUrl, int threshold) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("number", nin);
        fields.put("image", selfieUrl);
        fields.put("face_confidence_threshold", threshold);
        JsonNode body = post(properties.getNinFacePath(), fields, "NIN ending " + last4(nin));
        return body == null ? null : NinResult.from(body, keepable(body));
    }

    /** Whether the selfie is a real, live person rather than a photo of a photo, a screen or an AI image. */
    public LivenessResult checkLiveness(String selfieUrl) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("image", selfieUrl);
        JsonNode body = post(properties.getLivenessPath(), fields, "selfie liveness");
        return body == null ? null : LivenessResult.from(body, keepable(body));
    }

    private static String last4(String value) {
        return value == null || value.length() < 4 ? "" : value.substring(value.length() - 4);
    }

    public VehicleResult verifyPlateNumber(String plateNumber) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("vehicle_number", plateNumber);
        JsonNode body = post("/verification/vehicle", fields, "plate " + plateNumber);
        VehicleResult result = read(body, VehicleResult.class);
        return result == null ? null : result.withRawResponse(keepable(body));
    }

    /**
     * Serialises to a String rather than handing the map to the converter, so the request carries a
     * Content-Length instead of being chunked - Prembly answered "this field is required" for every
     * field of a body we definitely sent, which is what a gateway dropping a chunked body looks
     * like. The content type is stated outright for the same reason: not to be inferred.
     */
    private JsonNode post(String path, Map<String, ?> fields, String subject) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(fields);
        } catch (Exception e) {
            log.error("Could not build the Prembly request for {}: {}", subject, e.getMessage());
            return null;
        }
        try {
            return objectOrNull(restClient.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(JsonNode.class));
        } catch (HttpClientErrorException.BadRequest | HttpClientErrorException.UnprocessableEntity e) {
            // Prembly answers an input it won't accept (a malformed licence number) with a 4xx carrying
            // its normal status:false body - that is an answer about the driver's input, not an outage.
            log.warn("Prembly rejected the input for {} (sent {}): {}", subject, payload, e.getResponseBodyAsString());
            try {
                return objectOrNull(objectMapper.readTree(e.getResponseBodyAsString()));
            } catch (Exception unreadable) {
                return null;
            }
        } catch (Exception e) {
            // The payload is logged because the last failure was unreadable without it - it carries
            // a licence or plate number, both of which are already in the subject.
            log.warn("Prembly lookup failed for {} (sent {}): {}", subject, payload, e.getMessage());
            return null;
        }
    }

    /** Every Prembly answer is a JSON object - anything else is an outage page, not an answer. */
    private static JsonNode objectOrNull(JsonNode body) {
        return body != null && body.isObject() ? body : null;
    }

    private <T> T read(JsonNode body, Class<T> type) {
        if (body == null) {
            return null;
        }
        try {
            return objectMapper.treeToValue(body, type);
        } catch (Exception e) {
            log.warn("Prembly answered in a shape we could not read as {}: {}", type.getSimpleName(), e.getMessage());
            return null;
        }
    }

    /** Field names Prembly uses for images - the NIN record carries the NIMC photo under both data and nin_data. */
    private static final Set<String> IMAGE_FIELDS = Set.of("photo", "image", "picture", "signature");

    /** Longer than any name, address or reference - only an image (base64) under a name we don't know gets here. */
    private static final int MAX_KEPT_TEXT = 1_000;

    /**
     * The response as kept on the check: everything Prembly said, verbatim, except images. A photo is
     * tens of KB of biometric data we have no use for once the decision is made; it is replaced with
     * a marker so the record still shows one was returned.
     */
    static String keepable(JsonNode body) {
        JsonNode copy = body.deepCopy();
        stripImages(copy);
        return copy.toString();
    }

    private static void stripImages(JsonNode node) {
        if (node instanceof ObjectNode object) {
            List<String> names = new ArrayList<>();
            object.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                JsonNode value = object.get(name);
                if (isImage(name, value)) {
                    object.put(name, "[removed]");
                } else {
                    stripImages(value);
                }
            }
        } else if (node instanceof ArrayNode array) {
            for (int i = 0; i < array.size(); i++) {
                if (isImage(null, array.get(i))) {
                    array.set(i, TextNode.valueOf("[removed]"));
                } else {
                    stripImages(array.get(i));
                }
            }
        }
    }

    private static boolean isImage(String name, JsonNode value) {
        if (!value.isTextual() || value.asText().isBlank()) {
            return false;
        }
        return (name != null && IMAGE_FIELDS.contains(name.toLowerCase(Locale.ROOT)))
                || value.asText().length() > MAX_KEPT_TEXT;
    }

    /**
     * Both endpoints wrap the documented payload inside "data" and add an is_sandbox flag the docs
     * never mention - mapped from a real response rather than from the docs, which were wrong about
     * both. An unexpected shape is not guessed at here; it surfaces as a null the caller reports as
     * a provider error, so it can never be mistaken for the provider saying "no record".
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DriversLicenceResult(boolean status, String detail,
                                       @JsonProperty("response_code") String responseCode,
                                       @JsonProperty("is_sandbox") boolean sandbox,
                                       Data data,
                                       Verification verification,
                                       JsonNode errors,
                                       String rawResponse) {

        /** The response as kept on the check - set by the client, never read from Prembly's body. */
        DriversLicenceResult withRawResponse(String raw) {
            return new DriversLicenceResult(status, detail, responseCode, sandbox, data, verification, errors, raw);
        }

        /** Prembly's own field-level reason when it refused the input, e.g. a malformed licence number. */
        public String inputError() {
            return firstError(errors);
        }

        public FrscData frscData() {
            return data == null ? null : data.frscData();
        }

        public String reference() {
            return verification == null ? null : verification.reference();
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Data(@JsonProperty("frsc_data") FrscData frscData) {
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record FrscData(@JsonProperty("driversLicense") String driversLicense,
                               String firstname,
                               String lastname,
                               String middlename,
                               String birthdate) {
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record VehicleResult(boolean status, String detail,
                                @JsonProperty("response_code") String responseCode,
                                @JsonProperty("is_sandbox") boolean sandbox,
                                Data data,
                                Verification verification,
                                JsonNode errors,
                                String rawResponse) {

        /** The response as kept on the check - set by the client, never read from Prembly's body. */
        VehicleResult withRawResponse(String raw) {
            return new VehicleResult(status, detail, responseCode, sandbox, data, verification, errors, raw);
        }

        public String inputError() {
            return firstError(errors);
        }

        public String reference() {
            return verification == null ? null : verification.reference();
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Data(@JsonProperty("vehicle_number") String vehicleNumber,
                           @JsonProperty("vehicle_name") String vehicleName,
                           @JsonProperty("vehicle_color") String vehicleColor) {
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Verification(String status, String reference) {
    }

    /** What we need from a NIN answer (Basic or with face) - null fields mean the response didn't say; faceMatched is null for Basic. */
    public record NinResult(boolean status, String detail, boolean sandbox, String firstName, String lastName,
                            Boolean faceMatched, Double faceConfidence, String reference, String inputError,
                            String rawResponse) {

        static NinResult from(JsonNode root, String rawResponse) {
            JsonNode data = root.path("data").isObject() ? root.path("data") : root;
            JsonNode nin = first(data, root, "nin_data");
            if (nin == null && data.has("firstname")) {
                nin = data;
            }
            JsonNode face = first(data, root, "face_data");
            return new NinResult(
                    root.path("status").asBoolean(false),
                    text(root, "detail"),
                    root.path("is_sandbox").asBoolean(false),
                    nin == null ? null : text(nin, "firstname"),
                    nin == null ? null : (text(nin, "surname") != null ? text(nin, "surname") : text(nin, "lastname")),
                    face == null || !face.has("status") ? null : face.path("status").asBoolean(),
                    face == null || !face.has("confidence") ? null : face.path("confidence").asDouble(),
                    root.path("verification").isObject() ? text(root.path("verification"), "reference") : null,
                    firstError(root.get("errors")),
                    rawResponse);
        }

        private static JsonNode first(JsonNode data, JsonNode root, String field) {
            JsonNode node = data.path(field).isObject() ? data.path(field) : root.path(field);
            return node.isObject() ? node : null;
        }

        private static String text(JsonNode node, String field) {
            JsonNode value = node.get(field);
            return value == null || value.isNull() || value.asText().isBlank() ? null : value.asText();
        }
    }

    /** Face Liveliness Check - confidencePercent is data.confidence_in_percentage. */
    public record LivenessResult(boolean status, String detail, Double confidencePercent, String reference,
                                 String rawResponse) {

        static LivenessResult from(JsonNode root, String rawResponse) {
            JsonNode data = root.path("data");
            JsonNode percent = data.get("confidence_in_percentage");
            JsonNode reference = root.path("verification").get("reference");
            return new LivenessResult(root.path("status").asBoolean(false),
                    root.hasNonNull("detail") ? root.get("detail").asText() : null,
                    percent == null || percent.isNull() ? null : percent.asDouble(),
                    reference == null || reference.isNull() ? null : reference.asText(),
                    rawResponse);
        }
    }

    /** errors is {"number": ["..."]} - the first message of the first field, or null. */
    private static String firstError(JsonNode errors) {
        if (errors == null || !errors.isObject() || errors.isEmpty()) {
            return null;
        }
        JsonNode first = errors.elements().next();
        String message = first.isArray() && !first.isEmpty() ? first.get(0).asText() : first.asText();
        return message == null || message.isBlank() ? null : message;
    }
}
