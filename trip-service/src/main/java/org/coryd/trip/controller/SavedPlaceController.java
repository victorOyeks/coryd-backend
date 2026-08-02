package org.coryd.trip.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.coryd.trip.dto.SavePlaceRequest;
import org.coryd.trip.entity.SavedPlace;
import org.coryd.trip.service.SavedPlaceService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/saved-places")
@RequiredArgsConstructor
public class SavedPlaceController {

    private final SavedPlaceService savedPlaceService;

    @GetMapping("/riders/{riderId}")
    public ApiResponse<List<SavedPlace>> list(@PathVariable String riderId) {
        return ApiResponse.ok(savedPlaceService.getPlaces(riderId));
    }

    @PostMapping("/riders/{riderId}")
    public ApiResponse<SavedPlace> add(@PathVariable String riderId, @Valid @RequestBody SavePlaceRequest request) {
        return ApiResponse.ok(savedPlaceService.addPlace(riderId, request));
    }

    @DeleteMapping("/{placeId}")
    public ApiResponse<Void> delete(@PathVariable String placeId, @RequestParam String riderId) {
        savedPlaceService.deletePlace(riderId, placeId);
        return ApiResponse.ok("Saved place removed", null);
    }
}
