package org.coryd.profile.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.http.HttpTimeouts;
import org.coryd.profile.config.PremblyProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;

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

    public PremblyClient(PremblyProperties properties) {
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
        return post("/verification/drivers_license/advance/v2",
                java.util.Map.of("number", licenceNumber, "first_name", firstName, "last_name", lastName),
                DriversLicenceResult.class, "driver's licence " + licenceNumber);
    }

    public VehicleResult verifyPlateNumber(String plateNumber) {
        return post("/verification/vehicle",
                java.util.Map.of("vehicle_number", plateNumber),
                VehicleResult.class, "plate " + plateNumber);
    }

    private <T> T post(String path, Object body, Class<T> responseType, String subject) {
        try {
            return restClient.post()
                    .uri(path)
                    .body(body)
                    .retrieve()
                    .body(responseType);
        } catch (Exception e) {
            log.warn("Prembly lookup failed for {}: {}", subject, e.getMessage());
            return null;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DriversLicenceResult(boolean status, String detail,
                                       @JsonProperty("response_code") String responseCode,
                                       @JsonProperty("frsc_data") FrscData frscData) {

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
                                Data data) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Data(@JsonProperty("vehicle_number") String vehicleNumber,
                           @JsonProperty("vehicle_name") String vehicleName,
                           @JsonProperty("vehicle_color") String vehicleColor) {
        }
    }
}
