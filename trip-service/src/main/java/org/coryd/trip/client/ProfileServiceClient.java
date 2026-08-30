package org.coryd.trip.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.enums.VerificationStatus;
import org.coryd.common.security.ForwardedAuthInterceptor;
import org.coryd.common.security.InternalApiKeyInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.function.Function;


/**
 * Read-only lookups against profile-service for display data at booking time
 * (driver name, vehicle plate) - not for anything transactional, so a failure
 * here degrades to a blank snapshot rather than blocking the booking.
 */
@Slf4j
@Component
public class ProfileServiceClient {

    private final RestClient restClient;

    public ProfileServiceClient(@Value("${services.profile.url}") String profileServiceUrl,
                                 @Value("${services.internal.api-key}") String internalApiKey) {
        this.restClient = RestClient.builder()
                .baseUrl(profileServiceUrl)
                .requestInterceptor(new ForwardedAuthInterceptor())
                .requestInterceptor(new InternalApiKeyInterceptor(internalApiKey))
                .build();
    }

    public String fetchDriverName(String driverId) {
        try {
            return restClient.get()
                    .uri("/internal/drivers/{userId}", driverId)
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
                    .uri("/internal/vehicles/{vehicleId}", vehicleId)
                    .retrieve()
                    .body(VehicleView.class)
                    .data().plateNumber();
        } catch (Exception e) {
            log.warn("Could not fetch vehicle plate for {}: {}", vehicleId, e.getMessage());
            return null;
        }
    }

    /** One call for a whole search-result page, not one per driver - keyed by userId for easy zipping. */
    public Map<String, DriverSummaryItem> fetchDriverSummaries(List<String> driverIds) {
        if (driverIds == null || driverIds.isEmpty()) {
            return Map.of();
        }
        try {
            DriverSummaryBatchResponse response = restClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/internal/drivers/batch").queryParam("userIds", driverIds).build())
                    .retrieve()
                    .body(DriverSummaryBatchResponse.class);
            return toMap(response == null ? List.of() : response.data(), DriverSummaryItem::userId);
        } catch (Exception e) {
            log.warn("Could not fetch driver summaries for {}: {}", driverIds, e.getMessage());
            return Map.of();
        }
    }

    /** One call for a whole search-result page, not one per vehicle - keyed by vehicle id for easy zipping. */
    public Map<String, VehicleSummaryItem> fetchVehicleSummaries(List<String> vehicleIds) {
        if (vehicleIds == null || vehicleIds.isEmpty()) {
            return Map.of();
        }
        try {
            VehicleSummaryBatchResponse response = restClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/internal/vehicles/batch").queryParam("ids", vehicleIds).build())
                    .retrieve()
                    .body(VehicleSummaryBatchResponse.class);
            return toMap(response == null ? List.of() : response.data(), VehicleSummaryItem::id);
        } catch (Exception e) {
            log.warn("Could not fetch vehicle summaries for {}: {}", vehicleIds, e.getMessage());
            return Map.of();
        }
    }

    /** One call for a whole receipt's passenger list, not one per rider - keyed by userId for easy zipping. */
    public Map<String, RiderSummaryItem> fetchRiderSummaries(List<String> riderIds) {
        if (riderIds == null || riderIds.isEmpty()) {
            return Map.of();
        }
        try {
            RiderSummaryBatchResponse response = restClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/internal/riders/batch").queryParam("userIds", riderIds).build())
                    .retrieve()
                    .body(RiderSummaryBatchResponse.class);
            return toMap(response == null ? List.of() : response.data(), RiderSummaryItem::userId);
        } catch (Exception e) {
            log.warn("Could not fetch rider summaries for {}: {}", riderIds, e.getMessage());
            return Map.of();
        }
    }

    /** Best-effort: a route's chat is deleted from this service's own DB regardless of whether the
     * Cloudinary asset behind a voice note can be purged, so a failure here only logs a warning. */
    public void deleteMedia(String publicId, String resourceType) {
        try {
            restClient.delete()
                    .uri(uriBuilder -> uriBuilder.path("/internal/media")
                            .queryParam("publicId", publicId)
                            .queryParam("resourceType", resourceType)
                            .build())
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.warn("Could not delete Cloudinary media {}: {}", publicId, e.getMessage());
        }
    }

    /** Every emergency contact the rider has saved (Share Trip, SOS alert all of them, not just the primary). */
    public List<RiderContact> fetchRiderContacts(String riderId) {
        try {
            RiderContactBatchResponse response = restClient.get()
                    .uri("/internal/riders/{userId}/emergency-contacts", riderId)
                    .retrieve()
                    .body(RiderContactBatchResponse.class);
            return response == null || response.data() == null ? List.of() : response.data();
        } catch (Exception e) {
            log.warn("Could not fetch emergency contacts for {}: {}", riderId, e.getMessage());
            return List.of();
        }
    }

    private static <T> Map<String, T> toMap(List<T> items, Function<T, String> keyFn) {
        return items.stream().collect(java.util.stream.Collectors.toMap(keyFn, item -> item));
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DriverSummaryItem(String userId, String legalName, String photoUrl, VerificationStatus verificationStatus) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record DriverSummaryBatchResponse(List<DriverSummaryItem> data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record VehicleSummaryItem(String id, String make, String model, String plateNumber, boolean hasAC,
                                      Integer seatCapacity, VerificationStatus verificationStatus) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record VehicleSummaryBatchResponse(List<VehicleSummaryItem> data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RiderSummaryItem(String userId, String fullName, String photoUrl) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RiderSummaryBatchResponse(List<RiderSummaryItem> data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RiderContact(String name, String phone) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RiderContactBatchResponse(List<RiderContact> data) {
    }
}
