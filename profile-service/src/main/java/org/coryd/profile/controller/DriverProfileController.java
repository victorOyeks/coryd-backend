package org.coryd.profile.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.common.response.ApiResponse;
import org.coryd.common.security.CoRydUserDetails;
import org.coryd.profile.dto.DriverSummary;
import org.coryd.profile.dto.UpdateDriverProfileRequest;
import org.coryd.profile.entity.DriverProfile;
import org.coryd.profile.repository.DriverProfileRepository;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/drivers")
@RequiredArgsConstructor
public class DriverProfileController {

    private final DriverProfileRepository driverProfileRepository;

    @GetMapping("/{userId}")
    public ApiResponse<DriverProfile> get(@PathVariable String userId) {
        return ApiResponse.ok(driverProfileRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Driver profile not found")));
    }

    /** Batch lookup for search-result enrichment (trip-service) - one call per result page, not one per driver. */
    @GetMapping("/batch")
    public ApiResponse<List<DriverSummary>> batch(@RequestParam List<String> userIds) {
        return ApiResponse.ok(driverProfileRepository.findByUserIdIn(userIds).stream()
                .map(DriverSummary::from)
                .toList());
    }

    @GetMapping("/me")
    public ApiResponse<DriverProfile> getMine(@AuthenticationPrincipal CoRydUserDetails principal) {
        return ApiResponse.ok(driverProfileRepository.findByUserId(principal.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("Driver profile not found")));
    }

    @PutMapping("/me")
    public ApiResponse<DriverProfile> updateMine(@AuthenticationPrincipal CoRydUserDetails principal,
                                                  @Valid @RequestBody UpdateDriverProfileRequest request) {
        // Upsert: this is now the only place a DriverProfile row is ever created -
        // no event listener creates a shell in the background, so there's no race
        // to reason about here at all.
        DriverProfile profile = driverProfileRepository.findByUserId(principal.getUserId())
                .orElseGet(() -> DriverProfile.builder().userId(principal.getUserId()).build());

        profile.setLegalName(request.legalName());
        profile.setPhotoUrl(request.photoUrl());
        profile.setGovernmentIdUrl(request.governmentIdUrl());

        return ApiResponse.ok(driverProfileRepository.save(profile));
    }
}
