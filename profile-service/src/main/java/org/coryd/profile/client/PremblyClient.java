package org.coryd.profile.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.http.HttpTimeouts;
import org.coryd.profile.config.PremblyProperties;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

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

    public PremblyClient(PremblyProperties properties, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
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
        return post("/verification/drivers_license/advance/v2", fields,
                DriversLicenceResult.class, "driver's licence " + licenceNumber);
    }

    public VehicleResult verifyPlateNumber(String plateNumber) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("vehicle_number", plateNumber);
        return post("/verification/vehicle", fields, VehicleResult.class, "plate " + plateNumber);
    }

    /**
     * Serialises to a String rather than handing the map to the converter, so the request carries a
     * Content-Length instead of being chunked - Prembly answered "this field is required" for every
     * field of a body we definitely sent, which is what a gateway dropping a chunked body looks
     * like. The content type is stated outright for the same reason: not to be inferred.
     */
    private <T> T post(String path, Map<String, String> fields, Class<T> responseType, String subject) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(fields);
        } catch (Exception e) {
            log.error("Could not build the Prembly request for {}: {}", subject, e.getMessage());
            return null;
        }
        try {
            return restClient.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(responseType);
        } catch (HttpClientErrorException.BadRequest | HttpClientErrorException.UnprocessableEntity e) {
            // Prembly answers an input it won't accept (a malformed licence number) with a 4xx carrying
            // its normal status:false body - that is an answer about the driver's input, not an outage.
            log.warn("Prembly rejected the input for {} (sent {}): {}", subject, payload, e.getResponseBodyAsString());
            try {
                return objectMapper.readValue(e.getResponseBodyAsString(), responseType);
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
                                       JsonNode errors) {

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
                                JsonNode errors) {

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
