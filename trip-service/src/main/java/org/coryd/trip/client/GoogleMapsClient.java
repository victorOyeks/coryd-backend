package org.coryd.trip.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.exception.BadRequestException;
import org.coryd.trip.constants.GoogleMapsRoutes;
import org.coryd.trip.dto.DirectionsMultiStopResult;
import org.coryd.trip.dto.DistanceMatrixResult;
import org.coryd.trip.dto.GeocodeResult;
import org.coryd.trip.dto.PlaceSuggestion;
import org.coryd.trip.dto.RoutePreview;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.stream.Collectors;

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
        String polyline = route.overviewPolyline() == null ? null : route.overviewPolyline().points();
        return new RoutePreview(Math.round(distanceKm * 100.0) / 100.0, durationMinutes, leg.duration().text(), route.summary(), polyline);
    }

    /** Origin -> every waypoint -> destination as one road-following route, with Google choosing
     * the visiting order (optimize:true) - the driver/rider Live Trip map's multi-stop line.
     * Caller (RouteService) has already capped waypointCoords against Directions' own limit. */
    public DirectionsMultiStopResult getMultiStopRoutePreview(double originLat, double originLng,
                                                               double destLat, double destLng,
                                                               List<double[]> waypointCoords) {
        String waypointsParam = "optimize:true|" + waypointCoords.stream()
                .map(w -> w[0] + "," + w[1])
                .collect(Collectors.joining("|"));

        DirectionsResponse response = mapsRestClient.get()
                .uri(GoogleMapsRoutes.DIRECTIONS_WITH_WAYPOINTS, originLat + "," + originLng, destLat + "," + destLng,
                        waypointsParam, apiKey)
                .retrieve()
                .body(DirectionsResponse.class);
        if (response == null) throw new BadRequestException("No route found between the given points");
        if (STATUS_ZERO_RESULTS.equals(response.status())) {
            throw new BadRequestException("No driving route found between the given points");
        }
        if (!STATUS_OK.equals(response.status())) {
            log.warn("Directions (multi-stop) status: {} for {},{} -> {},{} with {} waypoints | reason: {}",
                    response.status(), originLat, originLng, destLat, destLng, waypointCoords.size(), response.errorMessage());
            throw new BadRequestException("Directions service unavailable - please try again");
        }

        DirectionsResponse.Route route = response.routes().getFirst();
        double distanceKm = route.legs().stream().mapToInt(leg -> leg.distance().value()).sum() / 1000.0;
        int durationMinutes = (int) Math.ceil(route.legs().stream().mapToInt(leg -> leg.duration().value()).sum() / 60.0);
        String polyline = route.overviewPolyline() == null ? null : route.overviewPolyline().points();
        List<Integer> waypointOrder = route.waypointOrder() == null ? List.of() : route.waypointOrder();

        return new DirectionsMultiStopResult(Math.round(distanceKm * 100.0) / 100.0, durationMinutes, polyline, waypointOrder);
    }

    /** The driver's road choices. Without via, every road Google offers (usual first); with via, the one road
     * passing through those points in order - "via:" points shape the road without becoming stops, and
     * Directions returns no alternatives when there are waypoints. */
    public List<RoutePreview> getRouteOptions(double originLat, double originLng, double destLat, double destLng,
                                              List<double[]> via) {
        String origin = originLat + "," + originLng;
        String destination = destLat + "," + destLng;
        DirectionsResponse response = via.isEmpty()
                ? mapsRestClient.get()
                        .uri(GoogleMapsRoutes.DIRECTIONS_ALTERNATIVES, origin, destination, apiKey)
                        .retrieve()
                        .body(DirectionsResponse.class)
                : mapsRestClient.get()
                        .uri(GoogleMapsRoutes.DIRECTIONS_WITH_WAYPOINTS, origin, destination,
                                via.stream().map(w -> "via:" + w[0] + "," + w[1]).collect(Collectors.joining("|")), apiKey)
                        .retrieve()
                        .body(DirectionsResponse.class);
        if (response == null) throw new BadRequestException("No route found between the given points");
        if (STATUS_ZERO_RESULTS.equals(response.status())) {
            throw new BadRequestException("No driving road goes through those places - try other ones");
        }
        if (!STATUS_OK.equals(response.status())) {
            log.warn("Directions (options) status: {} for {} -> {} with {} via points | reason: {}",
                    response.status(), origin, destination, via.size(), response.errorMessage());
            throw new BadRequestException("Directions service unavailable - please try again");
        }
        return response.routes().stream().map(route -> {
            int meters = route.legs().stream().mapToInt(leg -> leg.distance().value()).sum();
            int seconds = route.legs().stream().mapToInt(leg -> leg.duration().value()).sum();
            int minutes = (int) Math.ceil(seconds / 60.0);
            String polyline = route.overviewPolyline() == null ? null : route.overviewPolyline().points();
            return new RoutePreview(Math.round(meters / 10.0) / 100.0, minutes, durationText(minutes), route.summary(), polyline);
        }).toList();
    }

    /** Google's own wording ("1 hour 5 mins") for a total it didn't word itself. */
    private static String durationText(int minutes) {
        if (minutes < 60) {
            return minutes + (minutes == 1 ? " min" : " mins");
        }
        int hours = minutes / 60;
        int rest = minutes % 60;
        String h = hours + (hours == 1 ? " hour" : " hours");
        return rest == 0 ? h : h + " " + rest + (rest == 1 ? " min" : " mins");
    }

    /** Single origin, many destinations, one call - the search-results list's pickup-ETA column. Results come back in the same order as destinations; an individual pair's own failed status degrades to a null entry rather than failing the whole batch. */
    public List<DistanceMatrixResult> getDistanceMatrix(double originLat, double originLng, List<double[]> destinations) {
        String origins = originLat + "," + originLng;
        String destinationsParam = destinations.stream()
                .map(d -> d[0] + "," + d[1])
                .collect(Collectors.joining("|"));

        DistanceMatrixResponse response = mapsRestClient.get()
                .uri(GoogleMapsRoutes.DISTANCE_MATRIX, origins, destinationsParam, apiKey)
                .retrieve()
                .body(DistanceMatrixResponse.class);
        if (response == null || !STATUS_OK.equals(response.status())) {
            log.warn("Distance Matrix status: {} for origin {},{} with {} destinations",
                    response == null ? "null" : response.status(), originLat, originLng, destinations.size());
            throw new BadRequestException("Distance Matrix service unavailable - please try again");
        }

        List<DistanceMatrixResponse.Row.Element> elements = response.rows().getFirst().elements();
        return elements.stream()
                .map(e -> {
                    if (!STATUS_OK.equals(e.status()) || e.distance() == null || e.duration() == null) {
                        return new DistanceMatrixResult(null, null);
                    }
                    double distanceKm = e.distance().value() / 1000.0;
                    int durationMinutes = (int) Math.ceil(e.duration().value() / 60.0);
                    return new DistanceMatrixResult(Math.round(distanceKm * 100.0) / 100.0, durationMinutes);
                })
                .toList();
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
        private record Route(String summary, List<Leg> legs,
                              @JsonProperty("overview_polyline") OverviewPolyline overviewPolyline,
                              @JsonProperty("waypoint_order") List<Integer> waypointOrder) {
            @JsonIgnoreProperties(ignoreUnknown = true)
            private record Leg(Distance distance, Duration duration) {
                private record Distance(int value) {}
                private record Duration(int value, String text) {}
            }

            private record OverviewPolyline(String points) {}
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record DistanceMatrixResponse(List<Row> rows, String status) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        private record Row(List<Element> elements) {
            @JsonIgnoreProperties(ignoreUnknown = true)
            private record Element(String status, Distance distance, Duration duration) {
                private record Distance(int value) {}
                private record Duration(int value) {}
            }
        }
    }
}
