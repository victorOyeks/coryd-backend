package org.coryd.trip.controller.internal;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.coryd.common.enums.SosKind;
import org.coryd.common.enums.SosStatus;
import org.coryd.common.response.ApiResponse;
import org.coryd.common.response.PagedResponse;
import org.coryd.trip.dto.OpsSosItem;
import org.coryd.trip.dto.ops.OpsSosDtos;
import org.coryd.trip.entity.TripSafetyAlert;
import org.coryd.trip.service.OpsSosQueryService;
import org.coryd.trip.service.OpsSosService;
import org.coryd.trip.service.TripSafetyService;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** Service-to-service only (ops-service, for the Ops Console) - gated by InternalApiKeyFilter via the /internal/** prefix. */
@Hidden
@RestController
@RequestMapping("/internal/ops/sos")
@RequiredArgsConstructor
public class InternalOpsSosController {

    private final OpsSosQueryService opsSosQueryService;
    private final OpsSosService opsSosService;
    private final TripSafetyService tripSafetyService;

    /** The SOS queue - a status tab, filters, newest first. */
    @GetMapping("/queue")
    public ApiResponse<PagedResponse<OpsSosDtos.Row>> queue(@RequestParam(required = false) SosStatus status,
                                                            @RequestParam(required = false) SosKind kind,
                                                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                            @RequestParam(required = false) String routePostId,
                                                            @RequestParam(required = false) String riderId,
                                                            @RequestParam(required = false) String driverId,
                                                            @RequestParam(defaultValue = "0") int page,
                                                            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(opsSosService.list(new OpsSosDtos.Filter(status, kind, from, to, routePostId, riderId, driverId),
                PageRequest.of(Math.max(page, 0), clamp(size))));
    }

    @GetMapping("/counts")
    public ApiResponse<OpsSosDtos.Counts> counts(@RequestParam(required = false) SosKind kind,
                                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                 @RequestParam(required = false) String routePostId,
                                                 @RequestParam(required = false) String riderId,
                                                 @RequestParam(required = false) String driverId) {
        return ApiResponse.ok(opsSosService.counts(new OpsSosDtos.Filter(null, kind, from, to, routePostId, riderId, driverId)));
    }

    /** The original flat list, newest first - kept for anything still reading it. */
    @GetMapping
    public ApiResponse<PagedResponse<OpsSosItem>> sos(@RequestParam(defaultValue = "0") int page,
                                                      @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(opsSosQueryService.sos(PageRequest.of(page, Math.min(size, 50))));
    }

    /** "Are you OK?" checks raised on trips, newest first - with who answered what. */
    @GetMapping("/safety-alerts")
    public ApiResponse<PagedResponse<TripSafetyAlert>> safetyAlerts(@RequestParam(defaultValue = "0") int page,
                                                                    @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(PagedResponse.from(tripSafetyService.recent(PageRequest.of(page, Math.min(size, 50)))));
    }

    @GetMapping("/{incidentId}")
    public ApiResponse<OpsSosDtos.Detail> detail(@PathVariable String incidentId) {
        return ApiResponse.ok(opsSosService.detail(incidentId));
    }

    @PostMapping("/{incidentId}/acknowledge")
    public ApiResponse<OpsSosDtos.ActionResult> acknowledge(@PathVariable String incidentId,
                                                            @Valid @RequestBody OpsSosDtos.AcknowledgeRequest request) {
        return ApiResponse.ok("SOS acknowledged", opsSosService.acknowledge(incidentId, request));
    }

    @PostMapping("/{incidentId}/notes")
    public ApiResponse<OpsSosDtos.ActionResult> note(@PathVariable String incidentId, @Valid @RequestBody OpsSosDtos.NoteRequest request) {
        return ApiResponse.ok("Note added", opsSosService.note(incidentId, request));
    }

    @PostMapping("/{incidentId}/escalate")
    public ApiResponse<OpsSosDtos.ActionResult> escalate(@PathVariable String incidentId,
                                                         @Valid @RequestBody OpsSosDtos.EscalateRequest request) {
        return ApiResponse.ok("Escalation recorded", opsSosService.escalate(incidentId, request));
    }

    @PostMapping("/{incidentId}/resolve")
    public ApiResponse<OpsSosDtos.ActionResult> resolve(@PathVariable String incidentId,
                                                        @Valid @RequestBody OpsSosDtos.ResolveRequest request) {
        return ApiResponse.ok("SOS resolved", opsSosService.resolve(incidentId, request));
    }

    private static int clamp(int size) {
        return Math.max(1, Math.min(size, 50));
    }
}
