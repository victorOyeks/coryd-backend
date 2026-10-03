package org.coryd.ops.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.coryd.common.security.CoRydUserDetails;
import org.coryd.common.enums.VerificationQueueTab;
import org.coryd.common.response.PagedResponse;
import org.coryd.ops.dto.VerificationQueueCountsResponse;
import org.coryd.ops.dto.VerificationQueueItem;
import org.coryd.ops.dto.VerificationDecisionRequest;
import org.coryd.ops.dto.VerificationStatusResponse;
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
                                                                   @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, Math.min(size, 50));
        return ApiResponse.ok(driverVerificationOpsService.queue(tab, search, pageable));
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

    @PostMapping("/{driverId}/verification/decisions")
    public ApiResponse<VerificationStatusResponse> decide(@AuthenticationPrincipal CoRydUserDetails admin,
                                                          @PathVariable String driverId,
                                                          @Valid @RequestBody VerificationDecisionRequest request) {
        return ApiResponse.ok(driverVerificationOpsService.decide(driverId, request, admin));
    }
}
