package org.coryd.trip.insight;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongConsumer;

/**
 * Runs the trip-service reports: a JSON preview (first rows plus the full row count) and a CSV export streamed row by row
 * straight from the database cursor - never the whole result in memory - and capped at MAX_CSV_ROWS.
 */
@Service
@RequiredArgsConstructor
public class OpsReportService {

    public static final int MAX_PREVIEW_ROWS = 500;
    public static final int MAX_CSV_ROWS = 50_000;

    private final NamedParameterJdbcTemplate jdbc;

    /** The export cap - MAX_CSV_ROWS; lowered only by tests. */
    private int maxCsvRows = MAX_CSV_ROWS;

    public int maxCsvRows() {
        return maxCsvRows;
    }

    /** Tests only. */
    public void maxCsvRows(int rows) {
        this.maxCsvRows = rows;
    }

    /**
     * columnTypes: one per column (integer, decimal, money, date, string, boolean - ReportColumnType), and every row value
     * matches it. truncated: the range has more rows than the CSV export holds (MAX_CSV_ROWS) - narrow it. limited: this
     * preview shows fewer rows than the range has (the limit applied) - the CSV has them all.
     */
    public record Preview(String report, String title, LocalDate from, LocalDate to, String timezone, List<String> columns,
                          List<String> columnTypes, List<List<Object>> rows, long totalRows, boolean truncated, boolean limited) {
    }

    public Preview preview(TripReports report, LocalDate from, LocalDate to, int limit) {
        LagosRange range = LagosRange.of(from, to, 30);
        int max = Math.max(1, Math.min(limit, MAX_PREVIEW_ROWS));
        MapSqlParameterSource p = params(range).addValue("limit", max);
        List<ReportColumnType> types = report.columns().stream().map(ReportColumnType::of).toList();
        long[] total = {0};
        List<List<Object>> rows;
        if (report.inIndexOrder()) {
            total[0] = count(report, range);
            rows = jdbc.query("select * from (" + report.sql() + ") q limit :limit", p, (rs, i) -> row(rs, types));
        } else {
            // The total rides along on every row (a window over the whole result, before the limit), so the report runs once,
            // not once to count and again for the rows.
            rows = jdbc.query("select q.*, count(*) over () from (" + report.sql() + ") q limit :limit", p, (rs, i) -> {
                total[0] = rs.getLong(types.size() + 1);
                return row(rs, types);
            });
        }
        return new Preview(report.slug(), report.title(), range.from(), range.to(), LagosRange.LAGOS.getId(), report.columns(),
                ReportColumnType.labels(report.columns()), rows, total[0], total[0] > maxCsvRows, total[0] > rows.size());
    }

    public long count(TripReports report, LagosRange range) {
        Long n = jdbc.queryForObject("select count(*) from (" + report.sql() + ") q", params(range), Long.class);
        return n == null ? 0 : n;
    }

    /** Writes the header and at most maxCsvRows rows; returns how many rows were written. */
    public int csv(TripReports report, LagosRange range, Writer out) {
        return csv(report, range, out, total -> { });
    }

    /**
     * As above, telling total (every row in the range, not just the ones written) before anything is written - for the
     * response headers. Counted first for a report read in index order, else taken from the same single run as the rows.
     */
    public int csv(TripReports report, LagosRange range, Writer out, LongConsumer total) {
        int columns = report.columns().size();
        List<ReportColumnType> types = report.columns().stream().map(ReportColumnType::of).toList();
        MapSqlParameterSource p = params(range).addValue("limit", maxCsvRows);
        boolean counted = report.inIndexOrder();
        if (counted) {
            total.accept(count(report, range));
        }
        String sql = counted ? "select * from (" + report.sql() + ") q limit :limit"
                : "select q.*, count(*) over () from (" + report.sql() + ") q limit :limit";
        int[] written = {0};
        try {
            jdbc.query(sql, p, (RowCallbackHandler) rs -> {
                try {
                    if (written[0] == 0) {
                        if (!counted) {
                            total.accept(rs.getLong(columns + 1));
                        }
                        out.write(Csv.line(new ArrayList<>(report.columns())));
                    }
                    out.write(Csv.line(row(rs, types).stream().map(ReportColumnType::cell).toList()));
                    written[0]++;
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            if (written[0] == 0) {
                if (!counted) {
                    total.accept(0);
                }
                out.write(Csv.line(new ArrayList<>(report.columns())));
            }
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return written[0];
    }

    static MapSqlParameterSource params(LagosRange range) {
        return new MapSqlParameterSource().addValue("from", Timestamp.from(range.since())).addValue("to", Timestamp.from(range.until()));
    }

    /** Each value as its column's declared type (ReportColumnType.convert). */
    private static List<Object> row(ResultSet rs, List<ReportColumnType> types) throws SQLException {
        List<Object> row = new ArrayList<>(types.size());
        for (int c = 1; c <= types.size(); c++) {
            Object value = rs.getObject(c);
            if (value instanceof Double d) {
                value = BigDecimal.valueOf(d);
            }
            row.add(types.get(c - 1).convert(value));
        }
        return row;
    }
}
