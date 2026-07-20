package org.coryd.trip.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;


/**
 * Read-only lookups against profile-service for display data at booking time
 * (driver name, vehicle plate) - not for anything transactional, so a failure
 * here degrades to a blank snapshot rather than blocking the booking.
 */
@Slf4j
@Component
public class ProfileServiceClient {

    private final RestClient restClient;

    public ProfileServiceClient(@Value("${services.profile.url}") String profileServiceUrl) {
        this.restClient = RestClient.create(profileServiceUrl);
    }

    public String fetchDriverName(String driverId) {
        try {
            return restClient.get()
                    .uri("/drivers/{userId}", driverId)
                    .retrieve()
                    .body(DriverProfileView.class)
                    .data().legalName();
        } catch (Exception e) {
            log.warn("Could not fetch driver name for {}: {}", driverId, e.getMessage());
            return null;
        }
    }

    public String fetchVehiclePlate(String vehicleId) {
        if (vehicleId == null) {
            return null;
        }
        try {
            return restClient.get()
                    .uri("/vehicles/{vehicleId}", vehicleId)
                    .retrieve()
                    .body(VehicleView.class)
                    .data().plateNumber();
        } catch (Exception e) {
            log.warn("Could not fetch vehicle plate for {}: {}", vehicleId, e.getMessage());
            return null;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record DriverProfileView(Data data) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        private record Data(String legalName) {
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record VehicleView(Data data) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        private record Data(String plateNumber) {
        }
    }
}
