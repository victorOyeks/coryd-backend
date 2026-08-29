package org.coryd.profile.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.coryd.common.security.CoRydUserDetails;
import org.coryd.profile.dto.DriverOnboardingStatusResponse;
import org.coryd.profile.dto.UpdateDriverProfileRequest;
import org.coryd.profile.dto.VerificationDecisionBatchRequest;
import org.coryd.profile.dto.VerificationStatusResponse;
import org.coryd.profile.entity.DriverProfile;
import org.coryd.profile.service.DriverProfileService;
import org.coryd.profile.service.VerificationAggregationService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/drivers")
@RequiredArgsConstructor
public class DriverProfileController {

    private final DriverProfileService driverProfileService;
    private final VerificationAggregationService verificationAggregationService;

    @GetMapping("/me")
    public ApiResponse<DriverProfile> getMine(@AuthenticationPrincipal CoRydUserDetails principal) {
        return ApiResponse.ok(driverProfileService.get(principal.getUserId()));
    }

    @PutMapping("/me")
    public ApiResponse<DriverProfile> updateMine(@AuthenticationPrincipal CoRydUserDetails principal,
                                                  @Valid @RequestBody UpdateDriverProfileRequest request) {
        return ApiResponse.ok(driverProfileService.updateMine(principal.getUserId(), request));
    }

    /** Driver-side equivalent of identity-service's rider GET /auth/onboarding-status - lets the driver app resume onboarding at the right screen. Self-only (like /me above), not driverId-scoped - avoids the id/userId mixup a driverId path param invites. */
    @GetMapping("/me/onboarding-status")
    public ApiResponse<DriverOnboardingStatusResponse> onboardingStatus(@AuthenticationPrincipal CoRydUserDetails principal) {
        return ApiResponse.ok(verificationAggregationService.getOnboardingStatus(principal.getUserId()));
    }

    /** Ops admin's single review call - approve/reject any/all of this driver's reviewable items (identity, vehicle, documents) in one request. */
    @PatchMapping("/{driverId}/verification")
    public ApiResponse<VerificationStatusResponse> decideVerification(@AuthenticationPrincipal CoRydUserDetails principal,
                                                                        @PathVariable String driverId,
                                                                        @Valid @RequestBody VerificationDecisionBatchRequest request) {
        return ApiResponse.ok(verificationAggregationService.decide(driverId, principal.getActiveRole(), request));
    }

    @GetMapping("/{driverId}/verification-status")
    public ApiResponse<VerificationStatusResponse> verificationStatus(@PathVariable String driverId) {
        return ApiResponse.ok(verificationAggregationService.getStatus(driverId));
    }
}
