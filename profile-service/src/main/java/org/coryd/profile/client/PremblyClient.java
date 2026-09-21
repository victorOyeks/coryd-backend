package org.coryd.profile.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.http.HttpTimeouts;
import org.coryd.profile.config.PremblyProperties;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
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
        } catch (Exception e) {
            // The payload is logged because the last failure was unreadable without it - it carries
            // a licence or plate number, both of which are already in the subject.
            log.warn("Prembly lookup failed for {} (sent {}): {}", subject, payload, e.getMessage());
            return null;
        }
    }

    /**
     * The live response nests the documented payload one level down, inside "data", and carries an
     * is_sandbox flag the docs never mention. Both shapes are accepted because the docs have been
     * wrong about this endpoint more than once, and reading the wrong one silently turns a verified
     * licence into "no record found".
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DriversLicenceResult(boolean status, String detail,
                                       @JsonProperty("response_code") String responseCode,
                                       @JsonProperty("is_sandbox") boolean sandbox,
                                       @JsonProperty("frsc_data") FrscData rootFrscData,
                                       Data data,
                                       Verification verification) {

        public FrscData frscData() {
            if (rootFrscData != null) {
                return rootFrscData;
            }
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
                                Verification verification) {

        /** Same nesting caveat as the licence response - the fields may be one level deeper. */
        public Data vehicle() {
            if (data == null) {
                return null;
            }
            boolean populated = data.vehicleName() != null || data.vehicleNumber() != null;
            return populated ? data : data.data();
        }

        public String reference() {
            return verification == null ? null : verification.reference();
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Data(@JsonProperty("vehicle_number") String vehicleNumber,
                           @JsonProperty("vehicle_name") String vehicleName,
                           @JsonProperty("vehicle_color") String vehicleColor,
                           Data data) {
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Verification(String status, String reference) {
    }
}
