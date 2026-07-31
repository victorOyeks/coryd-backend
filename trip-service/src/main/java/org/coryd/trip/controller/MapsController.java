package org.coryd.trip.controller;

import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.coryd.trip.dto.GeocodeResult;
import org.coryd.trip.dto.PlaceSuggestion;
import org.coryd.trip.dto.RoutePreview;
import org.coryd.trip.service.MapsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/maps")
@RequiredArgsConstructor
public class MapsController {

    private final MapsService mapsService;

    @GetMapping("/places/autocomplete")
    public ApiResponse<List<PlaceSuggestion>> autocomplete(@RequestParam String input) {
        return ApiResponse.ok(mapsService.autocomplete(input));
    }

    @GetMapping("/places/details")
    public ApiResponse<GeocodeResult> placeDetails(@RequestParam String placeId) {
        return ApiResponse.ok(mapsService.getPlaceDetails(placeId));
    }

    @GetMapping("/geocode/reverse")
    public ApiResponse<GeocodeResult> reverseGeocode(@RequestParam double lat, @RequestParam double lng) {
        return ApiResponse.ok(mapsService.reverseGeocode(lat, lng));
    }

    @GetMapping("/route-preview")
    public ApiResponse<RoutePreview> routePreview(@RequestParam double originLat, @RequestParam double originLng,
                                                   @RequestParam double destinationLat, @RequestParam double destinationLng) {
        return ApiResponse.ok(mapsService.getRoutePreview(originLat, originLng, destinationLat, destinationLng));
    }
}
