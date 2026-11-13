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

    public record Preview(String report, String title, LocalDate from, LocalDate to, String timezone, List<String> columns,
                          List<List<Object>> rows, long totalRows, boolean truncated) {
    }

    public Preview preview(TripReports report, LocalDate from, LocalDate to, int limit) {
        LagosRange range = LagosRange.of(from, to, 30);
        int max = Math.max(1, Math.min(limit, MAX_PREVIEW_ROWS));
        MapSqlParameterSource p = params(range).addValue("limit", max);
        long total = count(report, range);
        List<List<Object>> rows = jdbc.query("select * from (" + report.sql() + ") q limit :limit", p, (rs, i) -> row(rs, report.columns().size()));
        return new Preview(report.slug(), report.title(), range.from(), range.to(), LagosRange.LAGOS.getId(), report.columns(), rows, total,
                total > rows.size());
    }

    public long count(TripReports report, LagosRange range) {
        Long n = jdbc.queryForObject("select count(*) from (" + report.sql() + ") q", params(range), Long.class);
        return n == null ? 0 : n;
    }

    /** Writes the header and at most MAX_CSV_ROWS rows; returns how many rows were written. */
    public int csv(TripReports report, LagosRange range, Writer out) {
        MapSqlParameterSource p = params(range).addValue("limit", MAX_CSV_ROWS);
        int[] written = {0};
        try {
            out.write(Csv.line(new ArrayList<>(report.columns())));
            jdbc.query("select * from (" + report.sql() + ") q limit :limit", p, (RowCallbackHandler) rs -> {
                try {
                    out.write(Csv.line(row(rs, report.columns().size())));
                    written[0]++;
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return written[0];
    }

    static MapSqlParameterSource params(LagosRange range) {
        return new MapSqlParameterSource().addValue("from", Timestamp.from(range.since())).addValue("to", Timestamp.from(range.until()));
    }

    private static List<Object> row(ResultSet rs, int columns) throws SQLException {
        List<Object> row = new ArrayList<>(columns);
        for (int c = 1; c <= columns; c++) {
            Object value = rs.getObject(c);
            if (value instanceof BigDecimal d) {
                value = d.stripTrailingZeros().toPlainString();
            } else if (value instanceof Double d) {
                value = BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
            }
            row.add(value);
        }
        return row;
    }
}
