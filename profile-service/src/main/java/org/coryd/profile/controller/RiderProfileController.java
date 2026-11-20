package org.coryd.profile.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.coryd.common.security.CoRydUserDetails;
import org.coryd.profile.dto.RiderProfileResponse;
import org.coryd.profile.dto.RiderSummary;
import org.coryd.profile.dto.UpdateRiderProfileRequest;
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

    /**
     * Another rider's public card - name and photo only. Email and emergency contact are the rider's own business
     * (GET /riders/me), and this used to hand them to any signed-in user who had the id.
     */
    @GetMapping("/{userId}")
    public ApiResponse<RiderSummary> get(@PathVariable String userId) {
        return ApiResponse.ok(RiderSummary.from(riderProfileService.get(userId)));
    }

    @GetMapping("/me")
    public ApiResponse<RiderProfileResponse> getMine(@AuthenticationPrincipal CoRydUserDetails principal) {
        return ApiResponse.ok(riderProfileService.getMine(principal.getUserId()));
    }

    @PutMapping("/me")
    public ApiResponse<RiderProfileResponse> updateMine(@AuthenticationPrincipal CoRydUserDetails principal,
                                                         @Valid @RequestBody UpdateRiderProfileRequest request) {
        return ApiResponse.ok(riderProfileService.updateMine(principal.getUserId(), request));
    }
}
