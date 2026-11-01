package org.coryd.ops.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.coryd.common.security.CoRydUserDetails;
import org.coryd.common.enums.VerificationQueueTab;
import org.coryd.common.response.PagedResponse;
import org.coryd.ops.dto.*;
import org.coryd.ops.service.DriverVerificationOpsService;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/drivers")
@RequiredArgsConstructor
public class DriverVerificationController {

    private final DriverVerificationOpsService driverVerificationOpsService;

    /** Drivers with at least one item waiting for a decision - tab is omitted entirely for "All". */
    @GetMapping("/verification/queue")
    public ApiResponse<PagedResponse<VerificationQueueItem>> queue(@RequestParam(required = false) VerificationQueueTab tab,
                                                                   @RequestParam(required = false) String search,
                                                                   @RequestParam(defaultValue = "0") int page,
                                                                   @RequestParam(defaultValue = "20") int size,
                                                                   @RequestParam(required = false) org.coryd.common.enums.VerificationQueueSort sort,
                                                                   @RequestParam(required = false) org.springframework.data.domain.Sort.Direction direction) {
        Pageable pageable = PageRequest.of(page, Math.min(size, 50));
        return ApiResponse.ok(driverVerificationOpsService.queue(tab, search, pageable, sort, direction));
    }

    /** The tab badges - they follow the same search as the list. */
    @GetMapping("/verification/queue/counts")
    public ApiResponse<VerificationQueueCountsResponse> counts(@RequestParam(required = false) String search) {
        return ApiResponse.ok(driverVerificationOpsService.counts(search));
    }

    /** One driver's four items, their statuses, documents and automatic check results. */
    @GetMapping("/{driverId}/verification")
    public ApiResponse<VerificationStatusResponse> driver(@PathVariable String driverId) {
        return ApiResponse.ok(driverVerificationOpsService.driver(driverId));
    }

    /** Mark one item Verified, or Flagged with the reason the driver will see. Returns the review screen. */
    @org.springframework.web.bind.annotation.PutMapping("/{driverId}/verification/items/{item}/review")
    public ApiResponse<VerificationStatusResponse> mark(@AuthenticationPrincipal CoRydUserDetails admin,
                                                        @PathVariable String driverId,
                                                        @PathVariable org.coryd.common.enums.ReviewableItem item,
                                                        @Valid @RequestBody org.coryd.ops.dto.ReviewMarkRequest request) {
        return ApiResponse.ok(driverVerificationOpsService.mark(driverId, item, request, admin));
    }

    /** "Reset to Default" - the item goes back to Unreviewed. */
    @org.springframework.web.bind.annotation.DeleteMapping("/{driverId}/verification/items/{item}/review")
    public ApiResponse<VerificationStatusResponse> reset(@PathVariable String driverId,
                                                         @PathVariable org.coryd.common.enums.ReviewableItem item) {
        return ApiResponse.ok(driverVerificationOpsService.reset(driverId, item));
    }

    /** Approve Driver - every item approved or marked Verified, nothing flagged. */
    @PostMapping("/{driverId}/verification/approve")
    public ApiResponse<VerificationStatusResponse> approve(@AuthenticationPrincipal CoRydUserDetails admin,
                                                           @PathVariable String driverId,
                                                           @Valid @RequestBody(required = false) ApproveDriverRequest request) {
        return ApiResponse.ok("Driver approved", driverVerificationOpsService.approve(driverId, request, admin));
    }

    /** Reject - flagged items are rejected with their notes, items marked Verified are approved, the rest keep waiting. */
    @PostMapping("/{driverId}/verification/reject")
    public ApiResponse<VerificationStatusResponse> reject(@AuthenticationPrincipal CoRydUserDetails admin, @PathVariable String driverId) {
        return ApiResponse.ok("Driver rejected", driverVerificationOpsService.reject(driverId, admin));
    }

    /** Save Changes (Drivers Directory): flagged documents rejected - approved ones lose their approval - verified ones approved. */
    @PostMapping("/{driverId}/verification/save")
    public ApiResponse<VerificationStatusResponse> save(@AuthenticationPrincipal CoRydUserDetails admin, @PathVariable String driverId) {
        return ApiResponse.ok("Changes saved", driverVerificationOpsService.save(driverId, admin));
    }

    /** The Driver's Licence screen: what Smile ID read off the licence, and fresh links to the images it captured. */
    @GetMapping("/{driverId}/verification/licence")
    public ApiResponse<com.fasterxml.jackson.databind.JsonNode> licence(@PathVariable String driverId) {
        return ApiResponse.ok(driverVerificationOpsService.licence(driverId));
    }

    /** The audit log - every decision on this driver's items, newest first. */
    @GetMapping("/{driverId}/verification/history")
    public ApiResponse<List<VerificationDecisionHistoryItem>> history(@PathVariable String driverId) {
        return ApiResponse.ok(driverVerificationOpsService.history(driverId));
    }

    @PostMapping("/{driverId}/verification/decisions")
    public ApiResponse<VerificationStatusResponse> decide(@AuthenticationPrincipal CoRydUserDetails admin,
                                                          @PathVariable String driverId,
                                                          @Valid @RequestBody VerificationDecisionRequest request) {
        return ApiResponse.ok(driverVerificationOpsService.decide(driverId, request, admin));
    }
}
