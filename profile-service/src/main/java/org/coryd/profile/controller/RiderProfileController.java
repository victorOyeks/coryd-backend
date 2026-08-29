package org.coryd.profile.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.coryd.common.security.CoRydUserDetails;
import org.coryd.profile.dto.UpdateRiderProfileRequest;
import org.coryd.profile.entity.RiderProfile;
import org.coryd.profile.service.RiderProfileService;
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

    private final RiderProfileService riderProfileService;

    @GetMapping("/{userId}")
    public ApiResponse<RiderProfile> get(@PathVariable String userId) {
        return ApiResponse.ok(riderProfileService.get(userId));
    }

    @GetMapping("/me")
    public ApiResponse<RiderProfile> getMine(@AuthenticationPrincipal CoRydUserDetails principal) {
        return ApiResponse.ok(riderProfileService.get(principal.getUserId()));
    }

    @PutMapping("/me")
    public ApiResponse<RiderProfile> updateMine(@AuthenticationPrincipal CoRydUserDetails principal,
                                                 @Valid @RequestBody UpdateRiderProfileRequest request) {
        return ApiResponse.ok(riderProfileService.updateMine(principal.getUserId(), request));
    }
}
