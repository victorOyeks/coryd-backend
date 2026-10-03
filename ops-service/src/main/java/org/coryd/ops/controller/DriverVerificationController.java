package org.coryd.ops.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.coryd.common.security.CoRydUserDetails;
import org.coryd.ops.service.DriverVerificationOpsService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Response and request shapes are profile-service's own (see its driver verification docs) - relayed as-is. */
@RestController
@RequestMapping("/drivers")
@RequiredArgsConstructor
public class DriverVerificationController {

    private final DriverVerificationOpsService driverVerificationOpsService;

    /** Drivers with at least one item waiting for a decision. */
    @GetMapping("/verification/queue")
    public ApiResponse<JsonNode> queue() {
        return ApiResponse.ok(driverVerificationOpsService.queue());
    }

    /** One driver's four items, their statuses, documents and automatic check results. */
    @GetMapping("/{driverId}/verification")
    public ApiResponse<JsonNode> driver(@PathVariable String driverId) {
        return ApiResponse.ok(driverVerificationOpsService.driver(driverId));
    }

    /** Body: { "decisions": [ { "item": "VEHICLE", "status": "APPROVED" | "REJECTED", "rejectionReason": "..." } ] } */
    @PostMapping("/{driverId}/verification/decisions")
    public ApiResponse<JsonNode> decide(@AuthenticationPrincipal CoRydUserDetails admin,
                                        @PathVariable String driverId,
                                        @RequestBody ObjectNode decisionBatch) {
        return ApiResponse.ok(driverVerificationOpsService.decide(driverId, decisionBatch, admin));
    }
}
