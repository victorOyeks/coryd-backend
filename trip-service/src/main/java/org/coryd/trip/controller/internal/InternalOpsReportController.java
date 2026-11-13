package org.coryd.trip.controller.internal;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.coryd.common.enums.IssueCategory;
import org.coryd.common.enums.IssueReportStatus;
import org.coryd.common.response.ApiResponse;
import org.coryd.common.response.PagedResponse;
import org.coryd.trip.dto.ops.OpsReportDtos;
import org.coryd.trip.service.OpsIssueReportService;
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

/** Reports & disputes - service-to-service only (ops-service), gated by InternalApiKeyFilter via the /internal/** prefix. */
@Hidden
@RestController
@RequestMapping("/internal/ops/reports")
@RequiredArgsConstructor
public class InternalOpsReportController {

    private final OpsIssueReportService service;

    @GetMapping
    public ApiResponse<PagedResponse<OpsReportDtos.Row>> list(@RequestParam(required = false) IssueReportStatus status,
                                                              @RequestParam(required = false) IssueCategory category,
                                                              @RequestParam(required = false) String reporterRole,
                                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                              @RequestParam(required = false) String routePostId,
                                                              @RequestParam(required = false) String assignedTo,
                                                              @RequestParam(required = false) String userId,
                                                              @RequestParam(required = false) String search,
                                                              @RequestParam(defaultValue = "0") int page,
                                                              @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.list(new OpsReportDtos.Filter(status, category, reporterRole, from, to, routePostId, assignedTo,
                userId, search), PageRequest.of(Math.max(page, 0), Math.max(1, Math.min(size, 50)))));
    }

    @GetMapping("/counts")
    public ApiResponse<OpsReportDtos.Counts> counts(@RequestParam(required = false) IssueCategory category,
                                                    @RequestParam(required = false) String reporterRole,
                                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                    @RequestParam(required = false) String routePostId,
                                                    @RequestParam(required = false) String assignedTo,
                                                    @RequestParam(required = false) String userId,
                                                    @RequestParam(required = false) String search) {
        return ApiResponse.ok(service.counts(new OpsReportDtos.Filter(null, category, reporterRole, from, to, routePostId, assignedTo,
                userId, search)));
    }

    @GetMapping("/{reportId}")
    public ApiResponse<OpsReportDtos.Detail> detail(@PathVariable String reportId) {
        return ApiResponse.ok(service.detail(reportId));
    }

    @PostMapping("/{reportId}/assign")
    public ApiResponse<OpsReportDtos.ActionResult> assign(@PathVariable String reportId, @Valid @RequestBody OpsReportDtos.AssignRequest request) {
        return ApiResponse.ok("Report assigned", service.assign(reportId, request));
    }

    @PostMapping("/{reportId}/notes")
    public ApiResponse<OpsReportDtos.ActionResult> note(@PathVariable String reportId, @Valid @RequestBody OpsReportDtos.NoteRequest request) {
        return ApiResponse.ok("Note added", service.note(reportId, request));
    }

    @PostMapping("/{reportId}/resolve")
    public ApiResponse<OpsReportDtos.ActionResult> resolve(@PathVariable String reportId, @Valid @RequestBody OpsReportDtos.ResolveRequest request) {
        return ApiResponse.ok("Report resolved", service.resolve(reportId, request));
    }

    @PostMapping("/{reportId}/dismiss")
    public ApiResponse<OpsReportDtos.ActionResult> dismiss(@PathVariable String reportId, @Valid @RequestBody OpsReportDtos.DismissRequest request) {
        return ApiResponse.ok("Report dismissed", service.dismiss(reportId, request));
    }
}
