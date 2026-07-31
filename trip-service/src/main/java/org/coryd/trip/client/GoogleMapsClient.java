package org.coryd.trip.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.exception.BadRequestException;
import org.coryd.trip.constants.GoogleMapsRoutes;
import org.coryd.trip.dto.GeocodeResult;
import org.coryd.trip.dto.PlaceSuggestion;
import org.coryd.trip.dto.RoutePreview;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/** Thin wrapper over Google Maps Platform - one method per endpoint, Google's own status vocabulary surfaced as exceptions. Caching/circuit-breaking lives one layer up, in MapsService. */
@Slf4j
@Component
@RequiredArgsConstructor
public class GoogleMapsClient {

    private final RestClient mapsRestClient;

    @Value("${google.maps.api-key}")
    private String apiKey;

    private static final String STATUS_OK              = "OK";
    private static final String STATUS_ZERO_RESULTS    = "ZERO_RESULTS";
    private static final String STATUS_NOT_FOUND       = "NOT_FOUND";
    private static final String STATUS_INVALID_REQUEST = "INVALID_REQUEST";

    public List<PlaceSuggestion> autocomplete(String input) {
        AutocompleteResponse response = mapsRestClient.get()
                .uri(GoogleMapsRoutes.AUTOCOMPLETE, input, apiKey)
                .retrieve()
                .body(AutocompleteResponse.class);
        if (response == null || STATUS_ZERO_RESULTS.equals(response.status())) return List.of();
        if (!STATUS_OK.equals(response.status())) {
            log.warn("Places Autocomplete status: {} for input: {}", response.status(), input);
            return List.of();
        }
        return response.predictions().stream()
                .map(p -> new PlaceSuggestion(p.description(), p.placeId()))
                .toList();
    }

    public GeocodeResult reverseGeocode(double lat, double lng) {
        GeocodingResponse response = mapsRestClient.get()
                .uri(GoogleMapsRoutes.REVERSE_GEOCODE, lat + "," + lng, apiKey)
                .retrieve()
                .body(GeocodingResponse.class);
        if (response == null) throw new BadRequestException("No results for the given coordinates");
        if (STATUS_ZERO_RESULTS.equals(response.status())) {
            throw new BadRequestException("No address found for the given coordinates");
        }
        if (!STATUS_OK.equals(response.status())) {
            log.warn("Reverse Geocoding status: {} for {},{}", response.status(), lat, lng);
            throw new BadRequestException("Geocoding service unavailable - please try again");
        }
        GeocodingResponse.GeocodingResult first = response.results().getFirst();
        GeocodingResponse.GeocodingResult.Geometry.Location loc = first.geometry().location();
        return new GeocodeResult(first.formattedAddress(), loc.lat(), loc.lng());
    }

    public GeocodeResult getPlaceDetails(String placeId) {
        PlaceDetailsResponse response = mapsRestClient.get()
                .uri(GoogleMapsRoutes.PLACE_DETAILS, placeId, apiKey)
                .retrieve()
                .body(PlaceDetailsResponse.class);
        if (response == null) throw new BadRequestException("No details found for the given place");
        if (STATUS_NOT_FOUND.equals(response.status()) || STATUS_INVALID_REQUEST.equals(response.status())) {
            throw new BadRequestException("Place not found");
        }
        if (!STATUS_OK.equals(response.status())) {
            log.warn("Place Details status: {} for placeId: {}", response.status(), placeId);
            throw new BadRequestException("Place Details service unavailable - please try again");
        }
        PlaceDetailsResponse.PlaceResult result = response.result();
        PlaceDetailsResponse.PlaceResult.Geometry.Location loc = result.geometry().location();
        return new GeocodeResult(result.formattedAddress(), loc.lat(), loc.lng());
    }

    public RoutePreview getRoutePreview(double originLat, double originLng, double destLat, double destLng) {
        DirectionsResponse response = mapsRestClient.get()
                .uri(GoogleMapsRoutes.DIRECTIONS, originLat + "," + originLng, destLat + "," + destLng, apiKey)
                .retrieve()
                .body(DirectionsResponse.class);
        if (response == null) throw new BadRequestException("No route found between the given points");
        if (STATUS_ZERO_RESULTS.equals(response.status())) {
            throw new BadRequestException("No driving route found between the given points");
        }
        if (!STATUS_OK.equals(response.status())) {
            log.warn("Directions status: {} for {},{} -> {},{} | reason: {}", response.status(),
                    originLat, originLng, destLat, destLng, response.errorMessage());
            throw new BadRequestException("Directions service unavailable - please try again");
        }
        DirectionsResponse.Route route = response.routes().getFirst();
        DirectionsResponse.Route.Leg leg = route.legs().getFirst();
        double distanceKm = leg.distance().value() / 1000.0;
        int durationMinutes = (int) Math.ceil(leg.duration().value() / 60.0);
        return new RoutePreview(Math.round(distanceKm * 100.0) / 100.0, durationMinutes, leg.duration().text(), route.summary());
    }

    // ── Google API response records (internal deserialization only) ──────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record AutocompleteResponse(List<Prediction> predictions, String status) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        private record Prediction(
                String description,
                @JsonProperty("place_id") String placeId
        ) {}
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GeocodingResponse(List<GeocodingResult> results, String status) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        private record GeocodingResult(
                @JsonProperty("formatted_address") String formattedAddress,
                Geometry geometry
        ) {
            @JsonIgnoreProperties(ignoreUnknown = true)
            private record Geometry(Location location) {
                private record Location(double lat, double lng) {}
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record PlaceDetailsResponse(PlaceResult result, String status) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        private record PlaceResult(
                String name,
                @JsonProperty("formatted_address") String formattedAddress,
                Geometry geometry
        ) {
            @JsonIgnoreProperties(ignoreUnknown = true)
            private record Geometry(Location location) {
                private record Location(double lat, double lng) {}
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record DirectionsResponse(List<Route> routes, String status,
                                       @JsonProperty("error_message") String errorMessage) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        private record Route(String summary, List<Leg> legs) {
            @JsonIgnoreProperties(ignoreUnknown = true)
            private record Leg(Distance distance, Duration duration) {
                private record Distance(int value) {}
                private record Duration(int value, String text) {}
            }
        }
    }
}
