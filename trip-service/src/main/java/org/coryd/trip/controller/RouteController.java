package org.coryd.trip.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.coryd.common.response.PagedResponse;
import org.coryd.common.security.CoRydUserDetails;
import org.coryd.trip.dto.DriverPostingEligibilityResponse;
import org.coryd.trip.dto.MultiStopRoutePreview;
import org.coryd.trip.dto.NearbyActivityResponse;
import org.coryd.trip.dto.PostRouteRequest;
import org.coryd.trip.dto.RideDetailsResponse;
import org.coryd.trip.dto.RideSearchResult;
import org.coryd.trip.dto.RouteSummary;
import org.coryd.trip.dto.SearchRequest;
import org.coryd.trip.entity.RoutePost;
import org.coryd.trip.entity.SuggestedFareRange;
import org.coryd.trip.service.RouteService;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/routes")
@RequiredArgsConstructor
public class RouteController {

    private final RouteService routeService;

    /** Whether this driver may post routes, and how long they have left to upload documents. Always self-scoped. */
    @GetMapping("/drivers/me/posting-eligibility")
    public ApiResponse<DriverPostingEligibilityResponse> postingEligibility(
            @AuthenticationPrincipal CoRydUserDetails principal) {
        return ApiResponse.ok(routeService.postingEligibility(principal.getUserId()));
    }

    @PostMapping("/drivers/{driverId}")
    public ApiResponse<RoutePost> post(@AuthenticationPrincipal CoRydUserDetails principal,
                                        @PathVariable String driverId, @Valid @RequestBody PostRouteRequest request) {
        return ApiResponse.ok(routeService.post(driverId, principal.getUserId(), request));
    }

    /** sessionId is optional and client-generated (a UUID kept for the life of the search screen) -
     * when present, the rider can subscribe to /topic/search-sessions/{sessionId} over WebSocket for
     * pushed updates instead of polling this endpoint again. See LiveSearchPushService. */
    @PostMapping("/search")
    public ApiResponse<List<RideSearchResult>> search(@AuthenticationPrincipal CoRydUserDetails principal,
                                                        @RequestParam(required = false) String sessionId,
                                                        @Valid @RequestBody SearchRequest request) {
        return ApiResponse.ok(routeService.search(request, sessionId, principal.getUserId()));
    }

    /** Called by the client only when /search comes back empty - relaxed radius/time window, capped result count. */
    @PostMapping("/search/nearby-options")
    public ApiResponse<List<RideSearchResult>> nearbyOptions(@Valid @RequestBody SearchRequest request) {
        return ApiResponse.ok(routeService.getNearbyOptions(request));
    }

    @GetMapping("/drivers/{driverId}")
    public ApiResponse<PagedResponse<RoutePost>> byDriver(@PathVariable String driverId,
                                                            @RequestParam(defaultValue = "0") int page,
                                                            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, Math.min(size, 50));
        return ApiResponse.ok(routeService.findByDriver(driverId, pageable));
    }

    /** Batch route resolution - e.g. the driver earnings screen's trip list turning routePostIds into "Ikorodu -> Victoria Island" text. */
    @GetMapping("/batch")
    public ApiResponse<List<RouteSummary>> batch(@RequestParam List<String> ids) {
        return ApiResponse.ok(routeService.getSummaries(ids));
    }

    @GetMapping("/nearby")
    public ApiResponse<NearbyActivityResponse> nearby(@AuthenticationPrincipal CoRydUserDetails principal,
                                                        @RequestParam double lat, @RequestParam double lng,
                                                        @RequestParam(defaultValue = "5") double radiusKm) {
        return ApiResponse.ok(routeService.getNearbyActivity(lat, lng, radiusKm, principal.getUserId()));
    }

    @GetMapping("/{routePostId}")
    public ApiResponse<RideDetailsResponse> details(@AuthenticationPrincipal CoRydUserDetails principal,
                                                      @PathVariable String routePostId,
                                                      @RequestParam double riderLat, @RequestParam double riderLng) {
        return ApiResponse.ok(routeService.getRouteDetails(routePostId, riderLat, riderLng, principal.getUserId()));
    }

    /** Multi-stop road-following line for the driver's Ready to Depart / Live Trip map and the
     * rider's Live Trip map - origin -> every still-active pickup -> destination. */
    @GetMapping("/{routePostId}/route-preview")
    public ApiResponse<MultiStopRoutePreview> multiStopRoutePreview(@PathVariable String routePostId) {
        return ApiResponse.ok(routeService.getMultiStopRoutePreview(routePostId));
    }

    @GetMapping("/suggested-fare")
    public ApiResponse<Optional<SuggestedFareRange>> suggestedFare(@RequestParam String originLabel,
                                                                     @RequestParam String destinationLabel) {
        return ApiResponse.ok(routeService.suggestedFare(originLabel, destinationLabel));
    }

    @DeleteMapping("/{routePostId}")
    public ApiResponse<Void> cancel(@AuthenticationPrincipal CoRydUserDetails principal, @PathVariable String routePostId) {
        routeService.cancel(routePostId, principal.getUserId());
        return ApiResponse.ok("Route cancelled", null);
    }

    /** "Start Trip" button on the Ready to Depart screen. */
    @PostMapping("/{routePostId}/start")
    public ApiResponse<Void> start(@AuthenticationPrincipal CoRydUserDetails principal, @PathVariable String routePostId) {
        routeService.startTrip(routePostId, principal.getUserId());
        return ApiResponse.ok("Trip started", null);
    }
}
