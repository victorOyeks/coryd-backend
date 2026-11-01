package org.coryd.profile.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.coryd.common.security.CoRydUserDetails;
import org.coryd.profile.dto.DriverOnboardingStatusResponse;
import org.coryd.profile.dto.DriverProfileResponse;
import org.coryd.profile.dto.DriverSummary;
import org.coryd.profile.dto.RiderSummary;
import org.coryd.profile.dto.NinLookupRequest;
import org.coryd.profile.dto.NinLookupResponse;
import org.coryd.profile.dto.SmileIdSession;
import org.coryd.profile.dto.UpdateDriverProfileRequest;
import org.coryd.profile.dto.VerificationDecisionBatchRequest;
import org.coryd.profile.dto.VerificationStatusResponse;
import org.coryd.profile.service.DriverProfileService;
import org.coryd.profile.entity.SmileIdCheckType;
import org.coryd.profile.service.NinLookupService;
import org.coryd.profile.service.SmileIdCheckService;
import org.coryd.profile.service.FavoriteDriverService;
import org.coryd.profile.service.VerificationAggregationService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.coryd.common.enums.VerificationQueueTab;
import org.coryd.common.response.PagedResponse;
import org.coryd.profile.dto.VerificationQueueCountsResponse;
import org.coryd.profile.dto.VerificationQueueItem;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/drivers")
@RequiredArgsConstructor
public class DriverProfileController {

    private final DriverProfileService driverProfileService;
    private final VerificationAggregationService verificationAggregationService;
    private final org.coryd.profile.service.VerificationReviewService verificationReviewService;
    private final org.coryd.profile.service.LicenceReviewService licenceReviewService;
    private final FavoriteDriverService favoriteDriverService;
    private final SmileIdCheckService smileIdCheckService;
    private final NinLookupService ninLookupService;

    @GetMapping("/me")
    public ApiResponse<DriverProfileResponse> getMine(@AuthenticationPrincipal CoRydUserDetails principal) {
        return ApiResponse.ok(driverProfileService.getMine(principal.getUserId()));
    }

    @PutMapping("/me")
    public ApiResponse<DriverProfileResponse> updateMine(@AuthenticationPrincipal CoRydUserDetails principal,
                                                          @Valid @RequestBody UpdateDriverProfileRequest request) {
        return ApiResponse.ok(driverProfileService.updateMine(principal.getUserId(), request));
    }

    /**
     * Opens a Smile ID licence check (Document Verification): the app runs Smile ID's SDK with what comes
     * back, then polls GET /me for governmentIdStatus, licenceCheckInProgress and licenceRetryReason.
     */
    @PostMapping("/me/licence-check/session")
    public ApiResponse<SmileIdSession> startLicenceCheck(@AuthenticationPrincipal CoRydUserDetails principal) {
        return ApiResponse.ok(smileIdCheckService.start(principal.getUserId(), principal.getActiveRole(), SmileIdCheckType.DRIVERS_LICENCE, null));
    }

    /**
     * The driver's NIN step: their NIN looked up with NIMC by number, no selfie (their licence check matches their
     * face). Answers CHECKING straight away; the result arrives within seconds - poll GET /me for ninStatus.
     */
    @PostMapping("/me/nin-lookup")
    public ApiResponse<NinLookupResponse> lookUpNin(@AuthenticationPrincipal CoRydUserDetails principal,
                                                    @Valid @RequestBody NinLookupRequest request) {
        return ApiResponse.ok(ninLookupService.start(principal.getUserId(), request.nin()));
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
    public ApiResponse<VerificationStatusResponse> verificationStatus(@AuthenticationPrincipal CoRydUserDetails principal,
                                                                       @PathVariable String driverId) {
        return ApiResponse.ok(verificationAggregationService.getStatusFor(
                driverId, principal.getUserId(), principal.getActiveRole()));
    }

    /** Ops admin's review queue - every driver with at least one item still awaiting a decision.
     * Without this, decide()/getStatus() above only work if ops already knows a driverId to look
     * up - there was previously no way to discover one. */
    @GetMapping("/verification/queue")
    public ApiResponse<PagedResponse<VerificationQueueItem>> verificationQueue(@AuthenticationPrincipal CoRydUserDetails principal,
                                                                               @RequestParam(required = false) VerificationQueueTab tab,
                                                                               @RequestParam(required = false) String search,
                                                                               @RequestParam(defaultValue = "0") int page,
                                                                               @RequestParam(defaultValue = "20") int size,
                                                                               @RequestParam(required = false) org.coryd.common.enums.VerificationQueueSort sort,
                                                                               @RequestParam(required = false) org.springframework.data.domain.Sort.Direction direction) {
        Pageable pageable = PageRequest.of(page, Math.min(size, 50));
        return ApiResponse.ok(verificationAggregationService.queue(principal.getActiveRole(), tab, search, pageable, sort, direction));
    }

    /** Ops review screen: each item's status and the reviewer's Verified / Flagged mark, and whether Approve Driver / Reject can be pressed. */
    @GetMapping("/{driverId}/verification/review")
    public ApiResponse<org.coryd.profile.dto.VerificationReviewResponse> verificationReview(@AuthenticationPrincipal CoRydUserDetails principal,
                                                                                         @PathVariable String driverId) {
        return ApiResponse.ok(verificationReviewService.review(driverId, principal.getActiveRole()));
    }

    /** Verified, or Flagged with the reason the driver will see. */
    @PutMapping("/{driverId}/verification/items/{item}/review")
    public ApiResponse<org.coryd.profile.dto.VerificationReviewResponse> markVerificationItem(@AuthenticationPrincipal CoRydUserDetails principal,
                                                                                           @PathVariable String driverId,
                                                                                           @PathVariable org.coryd.common.enums.ReviewableItem item,
                                                                                           @Valid @RequestBody org.coryd.profile.dto.ReviewMarkRequest request) {
        return ApiResponse.ok(verificationReviewService.mark(driverId, item, request, reviewerName(principal), principal.getActiveRole()));
    }

    /** "Reset to Default" - the item goes back to Unreviewed. */
    @DeleteMapping("/{driverId}/verification/items/{item}/review")
    public ApiResponse<org.coryd.profile.dto.VerificationReviewResponse> resetVerificationItem(@AuthenticationPrincipal CoRydUserDetails principal,
                                                                                            @PathVariable String driverId,
                                                                                            @PathVariable org.coryd.common.enums.ReviewableItem item) {
        return ApiResponse.ok(verificationReviewService.reset(driverId, item, principal.getActiveRole()));
    }

    /** Approve Driver - every item approved or marked Verified, nothing flagged. */
    @PostMapping("/{driverId}/verification/approve")
    public ApiResponse<VerificationStatusResponse> approveVerification(@AuthenticationPrincipal CoRydUserDetails principal,
                                                                       @PathVariable String driverId,
                                                                       @Valid @RequestBody(required = false) org.coryd.profile.dto.ReviewDecisionRequest request) {
        return ApiResponse.ok("Driver approved", verificationReviewService.approve(driverId, request, reviewerName(principal), principal.getActiveRole()));
    }

    /** Reject - flagged items are rejected with their notes, items marked Verified are approved, the rest keep waiting. */
    @PostMapping("/{driverId}/verification/reject")
    public ApiResponse<VerificationStatusResponse> rejectVerification(@AuthenticationPrincipal CoRydUserDetails principal,
                                                                      @PathVariable String driverId,
                                                                      @Valid @RequestBody(required = false) org.coryd.profile.dto.ReviewDecisionRequest request) {
        return ApiResponse.ok("Driver rejected", verificationReviewService.reject(driverId, request, reviewerName(principal), principal.getActiveRole()));
    }

    /** The licence as Smile ID saw it: what it read, and fresh links to the images it captured. Ops only. */
    @GetMapping("/{driverId}/verification/licence")
    public ApiResponse<org.coryd.profile.dto.LicenceReviewResponse> licenceReview(@AuthenticationPrincipal CoRydUserDetails principal,
                                                                                @PathVariable String driverId) {
        return ApiResponse.ok(licenceReviewService.licence(driverId, principal.getActiveRole()));
    }

    /** The audit log - every decision on this driver's items, newest first. */
    @GetMapping("/{driverId}/verification/history")
    public ApiResponse<List<org.coryd.profile.dto.VerificationDecisionHistoryItem>> verificationHistory(@AuthenticationPrincipal CoRydUserDetails principal,
                                                                                                       @PathVariable String driverId) {
        return ApiResponse.ok(verificationReviewService.history(driverId, principal.getActiveRole()));
    }

    private static String reviewerName(CoRydUserDetails principal) {
        String name = principal.getFullName();
        return name == null || name.isBlank() ? principal.getUserId() : name;
    }

    /** The queue's tab badges. */
    @GetMapping("/verification/queue/counts")
    public ApiResponse<VerificationQueueCountsResponse> verificationQueueCounts(@AuthenticationPrincipal CoRydUserDetails principal,
                                                                                @RequestParam(required = false) String search) {
        return ApiResponse.ok(verificationAggregationService.queueCounts(principal.getActiveRole(), search));
    }

    /** Public, rider-facing driver-profile lookup - a name/photo/verified-badge card, not the raw
     * profile (see DriverProfileService.getSummary for why). Restores what used to sit at this
     * exact path before the /internal/** refactor moved the raw-entity version there - this is a
     * new, narrower replacement, not the same endpoint back verbatim. */
    @GetMapping("/{driverId}")
    public ApiResponse<DriverSummary> get(@PathVariable String driverId) {
        return ApiResponse.ok(driverProfileService.getSummary(driverId));
    }

    /** The "reserve a seat for a favourite" picker on the route-posting screen - every rider who
     * has favourited this driver, name/photo included. */
    @GetMapping("/me/favorited-by")
    public ApiResponse<List<RiderSummary>> favoritedBy(@AuthenticationPrincipal CoRydUserDetails principal) {
        return ApiResponse.ok(favoriteDriverService.listRidersWhoFavourited(principal.getUserId()));
    }
}
