package org.coryd.ops.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.coryd.common.exception.BadRequestException;
import org.coryd.common.response.ApiResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Reports & analytics (docs/ops-console/dashboard-reports.md). Trip, booking, cancellation, driver, rider and corridor
 * reports come from trip-service (its CSV is streamed straight through); revenue from payment-service's finance summary and
 * the verification funnel from profile-service are small (a row per day / per stage) and laid out here. The emailed
 * daily/weekly/monthly reports are notification-service's own - sent or previewed on demand.
 *
 * <p>Under /reports alongside phase 5's issue reports: /reports/catalog, /reports/analytics/** and /reports/emailed/** are
 * fixed paths and never collide with an issue report's id.
 */
@RestController
@RequiredArgsConstructor
public class AnalyticsController {

    static final String REVENUE = "revenue";
    static final String FUNNEL = "verification-funnel";
    static final List<String> REVENUE_COLUMNS = List.of("date", "gross_bookings", "payments", "refunds", "commission", "payouts");
    static final List<String> REVENUE_TYPES = List.of("date", "money", "integer", "money", "money", "money");
    static final List<String> FUNNEL_COLUMNS = List.of("audience", "stage", "people", "percent_of_joined");
    static final List<String> FUNNEL_TYPES = List.of("string", "string", "integer", "decimal");
    static final Set<String> PERIODS = Set.of("DAILY", "WEEKLY", "MONTHLY");

    private final PlatformClient client;
    private final ObjectMapper objectMapper;

    @GetMapping("/reports/catalog")
    public ApiResponse<JsonNode> catalog() {
        ObjectNode out = objectMapper.createObjectNode();
        ArrayNode reports = out.putArray("reports");
        JsonNode trip = client.get(PlatformClient.Service.TRIP, "/internal/ops/analytics", null);
        if (trip != null) {
            trip.forEach(r -> reports.add(((ObjectNode) r.deepCopy()).put("source", "trip-service").put("csv", true)));
        }
        reports.add(item(REVENUE, "Revenue", "Per Lagos day: booking payments, refunds, CoRyd's commission and payouts to drivers.",
                REVENUE_COLUMNS, REVENUE_TYPES, "payment-service"));
        reports.add(item(FUNNEL, "Verification funnel", "Drivers and riders who joined in the range, by how far they got: documents, approval, first trip.",
                FUNNEL_COLUMNS, FUNNEL_TYPES, "profile-service"));
        ArrayNode emailed = out.putArray("emailed");
        for (String period : List.of("DAILY", "WEEKLY", "MONTHLY")) {
            emailed.addObject().put("period", period).put("send", "POST /ops/reports/emailed/" + period + "/send")
                    .put("preview", "GET /ops/reports/emailed/" + period + "/preview");
        }
        out.put("timezone", "Africa/Lagos").put("maxRangeDays", 366).put("maxCsvRows", 50_000).put("maxPreviewRows", 500);
        return ApiResponse.ok(out);
    }

    @GetMapping("/reports/analytics/{report}")
    public ApiResponse<JsonNode> preview(@PathVariable String report,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                         @RequestParam(defaultValue = "100") int limit) {
        if (REVENUE.equals(report) || FUNNEL.equals(report)) {
            ObjectNode local = local(report, from, to);
            int max = Math.max(1, Math.min(limit, 500));
            ArrayNode rows = (ArrayNode) local.get("rows");
            local.put("limited", rows.size() > max);
            while (rows.size() > max) {
                rows.remove(rows.size() - 1);
            }
            return ApiResponse.ok(local);
        }
        JsonNode preview = client.get(PlatformClient.Service.TRIP, "/internal/ops/analytics/" + slug(report),
                Params.of().put("from", from).put("to", to).put("limit", limit).build());
        return ApiResponse.ok(typed(preview));
    }

    /** Bounded (50,000 rows; X-Report-Truncated says when there were more) and streamed - never held in memory here. */
    @GetMapping("/reports/analytics/{report}.csv")
    public void csv(@PathVariable String report,
                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                    HttpServletResponse response) throws IOException {
        if (REVENUE.equals(report) || FUNNEL.equals(report)) {
            JsonNode data = local(report, from, to);
            response.setContentType("text/csv; charset=UTF-8");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setHeader("Content-Disposition", "attachment; filename=\"coryd-" + report + "-" + data.path("from").asText() + "-to-"
                    + data.path("to").asText() + ".csv\"");
            response.setHeader("X-Report-Rows", String.valueOf(data.path("totalRows").asLong()));
            response.setHeader("X-Report-Truncated", "false");
            Writer writer = response.getWriter();
            List<String> header = new ArrayList<>();
            data.path("columns").forEach(c -> header.add(c.asText()));
            writer.write(Csv.line(header));
            for (JsonNode row : data.path("rows")) {
                List<Object> cells = new ArrayList<>();
                row.forEach(c -> cells.add(c.isNull() ? null : c.isBigDecimal() ? c.decimalValue().toPlainString() : c.asText()));
                writer.write(Csv.line(cells));
            }
            writer.flush();
            return;
        }
        String slug = slug(report);
        client.stream(PlatformClient.Service.TRIP, "/internal/ops/analytics/" + slug + ".csv", Params.of().put("from", from).put("to", to).build(),
                response::setHeader, () -> {
                    response.setContentType("text/csv; charset=UTF-8");
                    try {
                        return response.getOutputStream();
                    } catch (IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                });
    }

    /** Emails the daily/weekly/monthly report now - date: any day in the period (default: the current one); to: other recipients. */
    @PostMapping("/reports/emailed/{period}/send")
    public ApiResponse<JsonNode> send(@PathVariable String period,
                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                      @RequestParam(required = false) List<String> to) {
        String p = period(period);
        return SettingsController.relay(client.send(PlatformClient.Service.NOTIFICATION, HttpMethod.POST, "/admin/reports/" + p,
                Params.of().put("date", date).put("to", to).build(), null));
    }

    @GetMapping(value = "/reports/emailed/{period}/preview", produces = MediaType.TEXT_HTML_VALUE)
    public String emailedPreview(@PathVariable String period,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return client.text(PlatformClient.Service.NOTIFICATION, "/admin/reports/" + period(period) + "/preview", Params.of().put("date", date).build());
    }

    private ObjectNode local(String report, LocalDate from, LocalDate to) {
        ObjectNode out = objectMapper.createObjectNode();
        ArrayNode columns = out.putArray("columns");
        ArrayNode rows = out.putArray("rows");
        if (REVENUE.equals(report)) {
            JsonNode summary = client.get(PlatformClient.Service.PAYMENT, "/internal/ops/money/finance/summary",
                    Params.of().put("from", from).put("to", to).build());
            REVENUE_COLUMNS.forEach(columns::add);
            for (JsonNode d : summary.path("daily")) {
                ArrayNode row = rows.addArray();
                row.add(d.path("date").asText());
                row.add(d.get("grossBookings"));
                row.add(d.get("payments"));
                row.add(d.get("refunds"));
                row.add(d.get("commission"));
                row.add(d.get("payouts"));
            }
            types(out, REVENUE_TYPES);
            out.put("report", REVENUE).put("title", "Revenue");
            out.set("from", summary.get("from"));
            out.set("to", summary.get("to"));
            out.set("totals", summary);
        } else {
            JsonNode funnel = client.get(PlatformClient.Service.PROFILE, "/internal/ops/verification-funnel",
                    Params.of().put("from", from).put("to", to).build());
            FUNNEL_COLUMNS.forEach(columns::add);
            for (JsonNode s : funnel.path("stages")) {
                ArrayNode row = rows.addArray();
                row.add(s.path("audience").asText());
                row.add(s.path("stage").asText());
                row.add(s.get("people"));
                row.add(s.get("percentOfJoined"));
            }
            types(out, FUNNEL_TYPES);
            out.put("report", FUNNEL).put("title", "Verification funnel");
            out.set("from", funnel.get("from"));
            out.set("to", funnel.get("to"));
        }
        out.put("timezone", "Africa/Lagos").put("totalRows", rows.size()).put("truncated", false).put("limited", false);
        typed(out);
        return out;
    }

    private void types(ObjectNode out, List<String> types) {
        ArrayNode columnTypes = out.putArray("columnTypes");
        types.forEach(columnTypes::add);
    }

    /**
     * Every row value as its column's declared type (columnTypes): integer and decimal JSON numbers, money a number with 2
     * decimal places, date/string text, boolean true/false; null stays null. Applied to every preview, so a number that
     * came through as text (or lost its scale on the way) still reads right.
     */
    static JsonNode typed(JsonNode preview) {
        if (!(preview instanceof ObjectNode out) || !out.path("rows").isArray() || !out.path("columnTypes").isArray()) {
            return preview;
        }
        List<String> types = new ArrayList<>();
        out.get("columnTypes").forEach(t -> types.add(t.asText()));
        com.fasterxml.jackson.databind.node.JsonNodeFactory f = com.fasterxml.jackson.databind.node.JsonNodeFactory.withExactBigDecimals(true);
        for (JsonNode row : out.get("rows")) {
            if (!(row instanceof ArrayNode cells)) {
                continue;
            }
            for (int c = 0; c < cells.size() && c < types.size(); c++) {
                JsonNode v = cells.get(c);
                if (v == null || v.isNull()) {
                    continue;
                }
                try {
                    switch (types.get(c)) {
                        case "integer" -> cells.set(c, f.numberNode(new java.math.BigDecimal(v.asText()).longValue()));
                        case "decimal" -> cells.set(c, f.numberNode(new java.math.BigDecimal(v.asText()).stripTrailingZeros()));
                        case "money" -> cells.set(c, f.numberNode(new java.math.BigDecimal(v.asText()).setScale(2, java.math.RoundingMode.HALF_UP)));
                        case "boolean" -> cells.set(c, f.booleanNode(v.isBoolean() ? v.booleanValue() : Boolean.parseBoolean(v.asText())));
                        default -> cells.set(c, f.textNode(v.asText()));
                    }
                } catch (NumberFormatException ignored) {
                    // leave a value that isn't a number as it came
                }
            }
        }
        if (!out.has("limited")) {
            out.put("limited", out.path("totalRows").asLong() > out.get("rows").size());
        }
        return out;
    }

    private ObjectNode item(String report, String title, String description, List<String> cols, List<String> types, String source) {
        ObjectNode node = objectMapper.createObjectNode().put("report", report).put("title", title).put("description", description)
                .put("source", source).put("csv", true);
        ArrayNode columns = node.putArray("columns");
        cols.forEach(columns::add);
        ArrayNode columnTypes = node.putArray("columnTypes");
        types.forEach(columnTypes::add);
        return node;
    }

    private static String slug(String report) {
        if (report == null || !report.matches("[a-z-]{1,40}")) {
            throw new BadRequestException("Unknown report '" + report + "'");
        }
        return report;
    }

    private static String period(String period) {
        String p = period == null ? "" : period.toUpperCase();
        if (!PERIODS.contains(p)) {
            throw new BadRequestException("period must be DAILY, WEEKLY or MONTHLY");
        }
        return p;
    }
}
