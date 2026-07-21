package org.coryd.profile.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.common.response.ApiResponse;
import org.coryd.common.security.CoRydUserDetails;
import org.coryd.profile.dto.UpdateRiderProfileRequest;
import org.coryd.profile.entity.RiderProfile;
import org.coryd.profile.repository.RiderProfileRepository;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/riders")
@RequiredArgsConstructor
public class RiderProfileController {

    private final RiderProfileRepository riderProfileRepository;

    @GetMapping("/{userId}")
    public ApiResponse<RiderProfile> get(@PathVariable String userId) {
        return ApiResponse.ok(riderProfileRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Rider profile not found")));
    }

    @GetMapping("/me")
    public ApiResponse<RiderProfile> getMine(@AuthenticationPrincipal CoRydUserDetails principal) {
        return ApiResponse.ok(riderProfileRepository.findByUserId(principal.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("Rider profile not found")));
    }

    @PutMapping("/me")
    public ApiResponse<RiderProfile> updateMine(@AuthenticationPrincipal CoRydUserDetails principal,
                                                 @Valid @RequestBody UpdateRiderProfileRequest request) {
        // Upsert, not find-or-404: the shell row is created asynchronously by
        // AccountEventListener reacting to AccountVerifiedEvent, with no guaranteed
        // ordering against the FE calling this endpoint right after OTP verification.
        RiderProfile profile = riderProfileRepository.findByUserId(principal.getUserId())
                .orElseGet(() -> RiderProfile.builder().userId(principal.getUserId()).build());

        profile.setFullName(request.fullName());
        profile.setEmail(request.email());
        profile.setPhotoUrl(request.photoUrl());

        return ApiResponse.ok(riderProfileRepository.save(profile));
    }
}
