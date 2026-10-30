package org.coryd.profile.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.coryd.common.response.PagedResponse;
import org.coryd.common.security.CoRydUserDetails;
import org.coryd.profile.dto.SmileIdSession;
import org.coryd.profile.dto.IdentityVerificationDecisionRequest;
import org.coryd.profile.dto.IdentityVerificationQueueItem;
import org.coryd.profile.dto.PartialNameMatchItem;
import org.coryd.profile.dto.IdentityVerificationResponse;
import org.coryd.profile.dto.StartIdentityCheckRequest;
import org.coryd.profile.dto.SubmitIdentityVerificationRequest;
import org.coryd.profile.entity.SmileIdCheckType;
import org.coryd.profile.service.IdentityVerificationService;
import org.coryd.profile.service.SmileIdCheckService;
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
 * Everyone's NIN + live selfie check through Smile ID - riders and drivers alike, once per person.
 * Nobody books or posts until it's approved. The queue and decisions are for ops admins.
 */
@RestController
@RequestMapping("/identity-verification")
@RequiredArgsConstructor
public class IdentityVerificationController {

    private final IdentityVerificationService identityVerificationService;
    private final SmileIdCheckService smileIdCheckService;

    /** Onboarding's identity step - opens a Smile ID check; the app runs Smile ID's SDK with what comes back, then polls GET /me. */
    @PostMapping("/session")
    public ApiResponse<SmileIdSession> startCheck(@AuthenticationPrincipal CoRydUserDetails principal,
                                                        @Valid @RequestBody StartIdentityCheckRequest request) {
        return ApiResponse.ok(smileIdCheckService.start(principal.getUserId(), principal.getActiveRole(), SmileIdCheckType.NIN, request.nin()));
    }

    /** The older step - a selfie uploaded via POST /media/upload, checked by ops. For app builds without Smile ID's SDK. */
    @Deprecated
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

    /** Ops: people approved on a partial name match (a married or reordered name), to check after the fact. */
    @GetMapping("/partial-name-matches")
    public ApiResponse<PagedResponse<PartialNameMatchItem>> partialNameMatches(@AuthenticationPrincipal CoRydUserDetails principal,
                                                                               @RequestParam(defaultValue = "0") int page,
                                                                               @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(identityVerificationService.partialNameMatches(principal.getActiveRole(), PageRequest.of(page, Math.min(size, 50))));
    }

    @PostMapping("/partial-name-matches/{checkId}/reviewed")
    public ApiResponse<Void> partialNameMatchReviewed(@AuthenticationPrincipal CoRydUserDetails principal, @PathVariable String checkId) {
        identityVerificationService.markPartialNameMatchReviewed(checkId, principal.getActiveRole(), principal.getUserId());
        return ApiResponse.ok("Marked as reviewed", null);
    }

    @PatchMapping("/{userId}")
    public ApiResponse<IdentityVerificationResponse> decide(@AuthenticationPrincipal CoRydUserDetails principal, @PathVariable String userId,
                                                            @Valid @RequestBody IdentityVerificationDecisionRequest request) {
        return ApiResponse.ok(identityVerificationService.decide(userId, principal.getActiveRole(), principal.getUserId(),
                request.approve(), request.reason()));
    }
}
