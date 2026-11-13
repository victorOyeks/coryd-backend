package org.coryd.ops.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.coryd.common.enums.IssueCategory;
import org.coryd.common.enums.IssueReportStatus;
import org.coryd.common.exception.BadRequestException;
import org.coryd.common.response.ApiResponse;
import org.coryd.common.response.PagedResponse;
import org.coryd.common.security.CoRydUserDetails;
import org.coryd.ops.dto.safety.SafetyRequests;
import org.coryd.ops.dto.safety.SafetyViews;
import org.coryd.ops.service.ReportOpsService;
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
import java.util.Locale;
import java.util.Set;

/** Reports & disputes - issue reports from riders and drivers, worked from OPEN to RESOLVED or DISMISSED. */
@RestController
@RequestMapping("/reports")
@RequiredArgsConstructor
public class ReportController {

    private static final Set<String> ROLES = Set.of("RIDER", "DRIVER");

    private final ReportOpsService reportOpsService;

    @GetMapping
    public ApiResponse<PagedResponse<SafetyViews.ReportItem>> list(@RequestParam(required = false) IssueReportStatus status,
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
        return ApiResponse.ok(reportOpsService.list(new ReportOpsService.Query(status, category, role(reporterRole), from, to, routePostId,
                assignedTo, userId, search), PageRequest.of(Math.max(page, 0), Math.max(1, Math.min(size, 50)))));
    }

    @GetMapping("/counts")
    public ApiResponse<SafetyViews.ReportCounts> counts(@RequestParam(required = false) IssueCategory category,
                                                        @RequestParam(required = false) String reporterRole,
                                                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                        @RequestParam(required = false) String routePostId,
                                                        @RequestParam(required = false) String assignedTo,
                                                        @RequestParam(required = false) String userId,
                                                        @RequestParam(required = false) String search) {
        return ApiResponse.ok(reportOpsService.counts(new ReportOpsService.Query(null, category, role(reporterRole), from, to, routePostId,
                assignedTo, userId, search)));
    }

    @GetMapping("/{reportId}")
    public ApiResponse<SafetyViews.ReportDetail> detail(@PathVariable String reportId) {
        return ApiResponse.ok(reportOpsService.detail(reportId));
    }

    /** To yourself with no body; to another admin with { "assigneeId", "assigneeName" }. */
    @PostMapping("/{reportId}/assign")
    public ApiResponse<SafetyViews.ReportActionResult> assign(@AuthenticationPrincipal CoRydUserDetails admin, @PathVariable String reportId,
                                                              @Valid @RequestBody(required = false) SafetyRequests.ReportAssign request) {
        return ApiResponse.ok("Report assigned", reportOpsService.assign(reportId, request, admin));
    }

    /** Internal note - the reporter never sees it. */
    @PostMapping("/{reportId}/notes")
    public ApiResponse<SafetyViews.ReportActionResult> note(@AuthenticationPrincipal CoRydUserDetails admin, @PathVariable String reportId,
                                                            @Valid @RequestBody SafetyRequests.ReportNote request) {
        return ApiResponse.ok("Note added", reportOpsService.note(reportId, request, admin));
    }

    @PostMapping("/{reportId}/resolve")
    public ApiResponse<SafetyViews.ReportActionResult> resolve(@AuthenticationPrincipal CoRydUserDetails admin, @PathVariable String reportId,
                                                               @Valid @RequestBody SafetyRequests.ReportResolve request) {
        return ApiResponse.ok("Report resolved", reportOpsService.resolve(reportId, request, admin));
    }

    @PostMapping("/{reportId}/dismiss")
    public ApiResponse<SafetyViews.ReportActionResult> dismiss(@AuthenticationPrincipal CoRydUserDetails admin, @PathVariable String reportId,
                                                               @Valid @RequestBody SafetyRequests.ReportDismiss request) {
        return ApiResponse.ok("Report dismissed", reportOpsService.dismiss(reportId, request, admin));
    }

    private static String role(String reporterRole) {
        if (reporterRole == null || reporterRole.isBlank()) {
            return null;
        }
        String role = reporterRole.trim().toUpperCase(Locale.ROOT);
        if (!ROLES.contains(role)) {
            throw new BadRequestException("reporterRole: must be RIDER or DRIVER");
        }
        return role;
    }
}
