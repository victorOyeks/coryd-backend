package org.coryd.ops.controller;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.coryd.ops.client.ProfileServiceClient;
import org.coryd.common.enums.IdentityReviewRole;
import org.coryd.common.enums.IdentityReviewSort;
import org.coryd.common.enums.IdentityReviewTab;
import org.coryd.common.security.CoRydUserDetails;
import org.coryd.ops.dto.IdentityVerificationDecision;
import org.coryd.ops.dto.OpsNoteRequest;
import org.coryd.ops.dto.OpsReasonRequest;
import org.coryd.ops.service.IdentityReviewOpsService;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Identity (NIN + live selfie) checks - riders and drivers - Smile ID couldn't settle on its own: the NIN's
 * last four digits and the check's note (the selfie is in the Smile ID portal under the job it names), then
 * approve or reject with a reason. Passed through as profile-service shapes it - this console adds nothing.
 */
@RestController
@RequestMapping("/identity-verification")
@RequiredArgsConstructor
public class IdentityVerificationController {

    private final ProfileServiceClient profileServiceClient;
    private final IdentityReviewOpsService identityReviewOpsService;

    @GetMapping("/queue")
    public ApiResponse<JsonNode> queue(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(profileServiceClient.identityVerificationQueue(page, Math.min(size, 50)));
    }

    /**
     * People approved although only some of their names matched the record (a married or reordered name) - each with
     * both names and the Smile ID job in summary. Mark it reviewed if it's the same person; if not, reject them with
     * the identity decision below (NIN_FACE / NIN_LOOKUP) or the driver's GOVERNMENT_ID decision (DRIVERS_LICENCE).
     */
    @GetMapping("/partial-name-matches")
    public ApiResponse<JsonNode> partialNameMatches(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(profileServiceClient.partialNameMatches(page, Math.min(size, 50)));
    }

    @PostMapping("/partial-name-matches/{checkId}/reviewed")
    public ApiResponse<Void> partialNameMatchReviewed(@PathVariable String checkId) {
        profileServiceClient.markPartialNameMatchReviewed(checkId);
        return ApiResponse.ok("Marked as reviewed", null);
    }

    /** The original approve-or-reject call - same rules as approve / reject below. */
    @PostMapping("/{userId}/decision")
    public ApiResponse<JsonNode> decide(@PathVariable String userId, @Valid @RequestBody IdentityVerificationDecision decision) {
        return ApiResponse.ok(profileServiceClient.decideIdentityVerification(userId, decision.approve(), decision.reason()));
    }

    // ---- the identity screen: tabs, one person, approve / reject / reset tries - see docs/ops-console/rider-identity.md

    @GetMapping("/people")
    public ApiResponse<JsonNode> people(@RequestParam(required = false) IdentityReviewTab tab,
                                        @RequestParam(required = false) String search,
                                        @RequestParam(required = false) IdentityReviewRole role,
                                        @RequestParam(required = false) IdentityReviewSort sort,
                                        @RequestParam(required = false) Sort.Direction direction,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(identityReviewOpsService.list(tab, search, role, sort, direction, Math.max(0, page),
                Math.max(1, Math.min(size, 50))));
    }

    @GetMapping("/people/counts")
    public ApiResponse<JsonNode> peopleCounts(@RequestParam(required = false) String search,
                                              @RequestParam(required = false) IdentityReviewRole role) {
        return ApiResponse.ok(identityReviewOpsService.counts(search, role));
    }

    @GetMapping("/people/{userId}")
    public ApiResponse<JsonNode> person(@PathVariable String userId) {
        return ApiResponse.ok(identityReviewOpsService.person(userId));
    }

    @GetMapping("/people/{userId}/history")
    public ApiResponse<JsonNode> personHistory(@PathVariable String userId) {
        return ApiResponse.ok(identityReviewOpsService.history(userId));
    }

    @GetMapping("/people/{userId}/checks/{checkId}/images")
    public ApiResponse<JsonNode> checkImages(@PathVariable String userId, @PathVariable String checkId) {
        return ApiResponse.ok(identityReviewOpsService.images(userId, checkId));
    }

    @PostMapping("/people/{userId}/approve")
    public ApiResponse<JsonNode> approve(@AuthenticationPrincipal CoRydUserDetails admin, @PathVariable String userId,
                                         @Valid @RequestBody(required = false) OpsNoteRequest request) {
        return ApiResponse.ok("Identity approved", identityReviewOpsService.approve(userId, request == null ? null : request.note(), admin));
    }

    @PostMapping("/people/{userId}/reject")
    public ApiResponse<JsonNode> reject(@AuthenticationPrincipal CoRydUserDetails admin, @PathVariable String userId,
                                        @Valid @RequestBody OpsReasonRequest request) {
        return ApiResponse.ok("Identity rejected", identityReviewOpsService.reject(userId, request.reason(), admin));
    }

    @PostMapping("/people/{userId}/reset-attempts")
    public ApiResponse<JsonNode> resetAttempts(@AuthenticationPrincipal CoRydUserDetails admin, @PathVariable String userId,
                                               @Valid @RequestBody(required = false) OpsNoteRequest request) {
        return ApiResponse.ok("Tries reset", identityReviewOpsService.resetAttempts(userId, request == null ? null : request.note(), admin));
    }
}
