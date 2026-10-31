package org.coryd.trip.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.coryd.common.security.CoRydUserDetails;
import org.coryd.trip.dto.SaveHomeRequest;
import org.coryd.trip.dto.SavePlaceRequest;
import org.coryd.trip.entity.SavedPlace;
import org.coryd.trip.service.SavedPlaceService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Saved Places screen - self-only (keyed by the caller's own userId from the JWT), works
 * identically for a rider or driver since a saved place isn't role-specific. Previously took
 * riderId as a plain path/query param with no check that it matched the caller - anyone with a
 * valid JWT could read or delete any other user's saved places by guessing their id. */
@RestController
@RequestMapping("/saved-places")
@RequiredArgsConstructor
public class SavedPlaceController {

    private final SavedPlaceService savedPlaceService;

    @GetMapping
    public ApiResponse<List<SavedPlace>> list(@AuthenticationPrincipal CoRydUserDetails principal) {
        return ApiResponse.ok(savedPlaceService.getPlaces(principal.getUserId()));
    }

    @PostMapping
    public ApiResponse<SavedPlace> add(@AuthenticationPrincipal CoRydUserDetails principal,
                                        @Valid @RequestBody SavePlaceRequest request) {
        return ApiResponse.ok(savedPlaceService.addPlace(principal.getUserId(), request));
    }

    /** The user's Home, or null data if they haven't set one. */
    @GetMapping("/home")
    public ApiResponse<SavedPlace> home(@AuthenticationPrincipal CoRydUserDetails principal) {
        return ApiResponse.ok(savedPlaceService.getHome(principal.getUserId()).orElse(null));
    }

    /** Sets Home - one per user, replacing the old one. Riders and drivers alike. */
    @PutMapping("/home")
    public ApiResponse<SavedPlace> saveHome(@AuthenticationPrincipal CoRydUserDetails principal,
                                            @Valid @RequestBody SaveHomeRequest request) {
        return ApiResponse.ok(savedPlaceService.saveHome(principal.getUserId(), request));
    }

    @DeleteMapping("/{placeId}")
    public ApiResponse<Void> delete(@AuthenticationPrincipal CoRydUserDetails principal, @PathVariable String placeId) {
        savedPlaceService.deletePlace(principal.getUserId(), placeId);
        return ApiResponse.ok("Saved place removed", null);
    }
}
