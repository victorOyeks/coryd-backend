package org.coryd.profile.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.coryd.common.response.PagedResponse;
import org.coryd.common.security.CoRydUserDetails;
import org.coryd.profile.dto.IdentityVerificationDecisionRequest;
import org.coryd.profile.dto.IdentityVerificationQueueItem;
import org.coryd.profile.dto.IdentityVerificationResponse;
import org.coryd.profile.dto.SubmitIdentityVerificationRequest;
import org.coryd.profile.service.IdentityVerificationService;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Everyone's NIN + selfie check - riders and drivers alike, once per person. Nobody books or posts
 * until it's approved. The queue and decisions are for ops admins.
 */
@RestController
@RequestMapping("/identity-verification")
@RequiredArgsConstructor
public class IdentityVerificationController {

    private final IdentityVerificationService identityVerificationService;

    /** Onboarding's identity step - NIN and a selfie (uploaded first via POST /media/upload). */
    @PostMapping
    public ApiResponse<IdentityVerificationResponse> submit(@AuthenticationPrincipal CoRydUserDetails principal,
                                                            @Valid @RequestBody SubmitIdentityVerificationRequest request) {
        return ApiResponse.ok(identityVerificationService.submit(principal.getUserId(), principal.getActiveRole(), request));
    }

    @GetMapping("/me")
    public ApiResponse<IdentityVerificationResponse> mine(@AuthenticationPrincipal CoRydUserDetails principal) {
        return ApiResponse.ok(identityVerificationService.mine(principal.getUserId()));
    }

    /** Ops queue - people whose check is waiting on a person, oldest first. */
    @GetMapping("/queue")
    public ApiResponse<PagedResponse<IdentityVerificationQueueItem>> queue(@AuthenticationPrincipal CoRydUserDetails principal,
                                                                           @RequestParam(defaultValue = "0") int page,
                                                                           @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(identityVerificationService.queue(principal.getActiveRole(), PageRequest.of(page, Math.min(size, 50))));
    }

    @PatchMapping("/{userId}")
    public ApiResponse<IdentityVerificationResponse> decide(@AuthenticationPrincipal CoRydUserDetails principal, @PathVariable String userId,
                                                            @Valid @RequestBody IdentityVerificationDecisionRequest request) {
        return ApiResponse.ok(identityVerificationService.decide(userId, principal.getActiveRole(), principal.getUserId(),
                request.approve(), request.reason()));
    }
}
