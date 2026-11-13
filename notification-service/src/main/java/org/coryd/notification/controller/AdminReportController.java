package org.coryd.notification.controller;

import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.coryd.common.security.CoRydUserDetails;
import org.coryd.notification.report.ReportPeriod;
import org.coryd.notification.service.ReportService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * Fires an ops report on demand rather than waiting for 06:00. OPS_ADMIN only - the role
 * check lives in ReportService alongside the rest of the logic.
 */
@RestController
@RequestMapping("/admin/reports")
@RequiredArgsConstructor
public class AdminReportController {

    private final ReportService reportService;
    private final org.coryd.notification.broadcast.BroadcastAuditWriter audit;

    /** date is any day inside the period to report on; omit it for today's period. */
    @PostMapping("/{period}")
    public ApiResponse<Void> send(@AuthenticationPrincipal CoRydUserDetails principal,
                                  @PathVariable ReportPeriod period,
                                  @RequestParam(required = false)
                                  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                  @RequestParam(required = false) List<String> to) {
        reportService.sendReportFor(period, date, principal.getActiveRole(), to);
        // Ops Console phase 6: a report sent on demand is an ops action - recorded with who asked for it.
        audit.write(principal.getUserId(), principal.getFullName(), "REPORT_SENT", "REPORT", period.name(), null,
                java.util.Map.of("date", String.valueOf(date), "to", to == null ? "default recipients" : String.join(", ", to)),
                "Sent on demand");
        return ApiResponse.ok("Report sent", null);
    }

    /** Same window as send(), rendered straight to the browser instead of emailed. */
    @GetMapping(value = "/{period}/preview", produces = MediaType.TEXT_HTML_VALUE)
    public String preview(@AuthenticationPrincipal CoRydUserDetails principal,
                          @PathVariable ReportPeriod period,
                          @RequestParam(required = false)
                          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return reportService.renderReportFor(period, date, principal.getActiveRole());
    }
}
