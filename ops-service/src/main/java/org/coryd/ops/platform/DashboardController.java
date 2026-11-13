package org.coryd.ops.platform;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** The console's landing dashboard on real data - docs/ops-console/dashboard-reports.md. */
@RestController
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;
    private final PlatformClient client;

    /** from/to: Lagos days, inclusive (default the last 7 days). fresh=true skips the minute-long cache. */
    @GetMapping("/dashboard")
    public ApiResponse<JsonNode> dashboard(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                           @RequestParam(defaultValue = "false") boolean fresh) {
        return ApiResponse.ok(dashboardService.dashboard(from, to, fresh));
    }

    /**
     * The pilot corridors: there is no stored corridor definition - matching, search and pricing key everything on the
     * normalised origin -> destination labels drivers post. This is the read-only view of the corridors seen in trips posted
     * in the range, with volumes and the suggested fare range (docs/ops-console/settings.md, "Corridors").
     */
    @GetMapping("/corridors")
    public ApiResponse<JsonNode> corridors(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                           @RequestParam(defaultValue = "200") int limit) {
        return ApiResponse.ok(client.get(PlatformClient.Service.TRIP, "/internal/ops/reports/corridors",
                Params.of().put("from", from).put("to", to).put("limit", limit).build()));
    }
}
