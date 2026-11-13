package org.coryd.ops.controller;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.coryd.common.enums.SosKind;
import org.coryd.common.enums.SosStatus;
import org.coryd.common.response.ApiResponse;
import org.coryd.common.response.PagedResponse;
import org.coryd.common.security.CoRydUserDetails;
import org.coryd.ops.client.TripServiceClient;
import org.coryd.ops.dto.safety.SafetyRequests;
import org.coryd.ops.dto.safety.SafetyViews;
import org.coryd.ops.service.SosOpsService;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** Safety & SOS: the SOS queue (SOS pressed + "Are you OK?" checks nobody answered), one incident, and handling it. */
@RestController
@RequestMapping("/sos")
@RequiredArgsConstructor
public class SosController {

    private final SosOpsService sosOpsService;
    private final TripServiceClient tripServiceClient;

    /** The queue - a status tab (omit for All), filters, newest first. from/to are Lagos days the incident was raised. */
    @GetMapping
    public ApiResponse<PagedResponse<SafetyViews.SosItem>> list(@RequestParam(required = false) SosStatus status,
                                                                @RequestParam(required = false) SosKind kind,
                                                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                                @RequestParam(required = false) String routePostId,
                                                                @RequestParam(required = false) String riderId,
                                                                @RequestParam(required = false) String driverId,
                                                                @RequestParam(defaultValue = "0") int page,
                                                                @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(sosOpsService.list(new SosOpsService.Query(status, kind, from, to, routePostId, riderId, driverId),
                PageRequest.of(Math.max(page, 0), Math.max(1, Math.min(size, 50)))));
    }

    /** Tab badges - same filters as the list, except status. */
    @GetMapping("/counts")
    public ApiResponse<SafetyViews.SosCounts> counts(@RequestParam(required = false) SosKind kind,
                                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                     @RequestParam(required = false) String routePostId,
                                                     @RequestParam(required = false) String riderId,
                                                     @RequestParam(required = false) String driverId) {
        return ApiResponse.ok(sosOpsService.counts(new SosOpsService.Query(null, kind, from, to, routePostId, riderId, driverId)));
    }

    /** "Are you OK?" checks raised on trips (long stop, off route, signal lost), newest first, with each answer - every check, answered or not. */
    @GetMapping("/safety-alerts")
    public ApiResponse<JsonNode> safetyAlerts(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(tripServiceClient.safetyAlerts(page, Math.min(size, 50)));
    }

    @GetMapping("/{incidentId}")
    public ApiResponse<SafetyViews.SosDetail> detail(@PathVariable String incidentId) {
        return ApiResponse.ok(sosOpsService.detail(incidentId));
    }

    /** Claim it - shows everyone which admin is handling it, and stops the re-alerts. Body optional: { "note": "…" }. */
    @PostMapping("/{incidentId}/acknowledge")
    public ApiResponse<SafetyViews.SosActionResult> acknowledge(@AuthenticationPrincipal CoRydUserDetails admin, @PathVariable String incidentId,
                                                                @Valid @RequestBody(required = false) SafetyRequests.SosAcknowledge request) {
        return ApiResponse.ok("SOS acknowledged", sosOpsService.acknowledge(incidentId, request, admin));
    }

    @PostMapping("/{incidentId}/notes")
    public ApiResponse<SafetyViews.SosActionResult> note(@AuthenticationPrincipal CoRydUserDetails admin, @PathVariable String incidentId,
                                                         @Valid @RequestBody SafetyRequests.SosNote request) {
        return ApiResponse.ok("Note added", sosOpsService.note(incidentId, request, admin));
    }

    /** Record who was called (police, ambulance, an emergency contact…), when, and their reference. */
    @PostMapping("/{incidentId}/escalate")
    public ApiResponse<SafetyViews.SosActionResult> escalate(@AuthenticationPrincipal CoRydUserDetails admin, @PathVariable String incidentId,
                                                             @Valid @RequestBody SafetyRequests.SosEscalate request) {
        return ApiResponse.ok("Escalation recorded", sosOpsService.escalate(incidentId, request, admin));
    }

    @PostMapping("/{incidentId}/resolve")
    public ApiResponse<SafetyViews.SosActionResult> resolve(@AuthenticationPrincipal CoRydUserDetails admin, @PathVariable String incidentId,
                                                            @Valid @RequestBody SafetyRequests.SosResolve request) {
        return ApiResponse.ok("SOS resolved", sosOpsService.resolve(incidentId, request, admin));
    }
}
