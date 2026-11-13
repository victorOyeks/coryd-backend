package org.coryd.trip.insight;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.coryd.common.security.CoRydUserDetails;
import org.coryd.trip.settings.OpsAdmin;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

/** The console's dashboard numbers and reports from trip-service - ops-service only, the admin's own token, OPS_ADMIN only. */
@Hidden
@RestController
@RequiredArgsConstructor
public class InternalOpsInsightController {

    private final OpsDashboardService dashboardService;
    private final OpsReportService reportService;

    @GetMapping("/internal/ops/dashboard")
    public ApiResponse<TripDashboard> dashboard(@AuthenticationPrincipal CoRydUserDetails principal,
                                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        OpsAdmin.of(principal);
        return ApiResponse.ok(dashboardService.dashboard(from, to));
    }

    public record CatalogItem(String report, String title, String description, List<String> columns) {
    }

    @GetMapping("/internal/ops/reports")
    public ApiResponse<List<CatalogItem>> catalog(@AuthenticationPrincipal CoRydUserDetails principal) {
        OpsAdmin.of(principal);
        return ApiResponse.ok(Arrays.stream(TripReports.values())
                .map(r -> new CatalogItem(r.slug(), r.title(), r.description(), r.columns())).toList());
    }

    @GetMapping("/internal/ops/reports/{report}")
    public ApiResponse<OpsReportService.Preview> preview(@AuthenticationPrincipal CoRydUserDetails principal, @PathVariable String report,
                                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                         @RequestParam(defaultValue = "100") int limit) {
        OpsAdmin.of(principal);
        return ApiResponse.ok(reportService.preview(TripReports.bySlug(report), from, to, limit));
    }

    /**
     * The CSV, streamed. X-Report-Rows: the rows in the range; X-Report-Truncated: true when there were more than the
     * MAX_CSV_ROWS the file holds (narrow the range).
     */
    @GetMapping(value = "/internal/ops/reports/{report}.csv")
    public void csv(@AuthenticationPrincipal CoRydUserDetails principal, @PathVariable String report,
                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                    HttpServletResponse response) throws IOException {
        OpsAdmin.of(principal);
        TripReports which = TripReports.bySlug(report);
        LagosRange range = LagosRange.of(from, to, 30);
        long total = reportService.count(which, range);
        response.setContentType("text/csv; charset=UTF-8");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Content-Disposition", "attachment; filename=\"coryd-" + which.slug() + "-" + range.from() + "-to-" + range.to() + ".csv\"");
        response.setHeader("X-Report-Rows", String.valueOf(total));
        response.setHeader("X-Report-Truncated", String.valueOf(total > reportService.maxCsvRows()));
        Writer writer = response.getWriter();
        reportService.csv(which, range, writer);
    }
}
