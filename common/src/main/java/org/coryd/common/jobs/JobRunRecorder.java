package org.coryd.common.jobs;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The last run of every scheduled job in this service - one row per job in {@code <schema>.job_runs}, upserted when a run
 * ends: when it started, how long it took, OK or FAILED with the error, plus run and failure totals. Switched on by
 * {@code coryd.job-runs.schema} (each service with a database names its own schema); off in ops-service, which has none.
 * A recording failure is logged and swallowed - it must never fail or slow the job it describes.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "coryd.job-runs.schema")
public class JobRunRecorder {

    private static final Pattern SCHEMA = Pattern.compile("[a-z_]+");

    private final JdbcTemplate jdbc;
    private final String table;

    public JobRunRecorder(JdbcTemplate jdbc, @Value("${coryd.job-runs.schema}") String schema) {
        if (!SCHEMA.matcher(schema).matches()) {
            throw new IllegalArgumentException("coryd.job-runs.schema must be a plain schema name: " + schema);
        }
        this.jdbc = jdbc;
        this.table = schema + ".job_runs";
    }

    public void record(String job, Instant startedAt, Duration took, Throwable error) {
        try {
            Instant finishedAt = startedAt.plus(took);
            boolean ok = error == null;
            String message = ok ? null : truncate(error.getClass().getSimpleName() + ": " + error.getMessage(), 500);
            jdbc.update("""
                    insert into %s (job_name, last_started_at, last_finished_at, last_duration_ms, last_status, last_error,
                                    last_success_at, last_failure_at, runs, failures, updated_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, 1, ?, now())
                    on conflict (job_name) do update set
                        last_started_at = excluded.last_started_at,
                        last_finished_at = excluded.last_finished_at,
                        last_duration_ms = excluded.last_duration_ms,
                        last_status = excluded.last_status,
                        last_error = coalesce(excluded.last_error, %2$s.last_error),
                        last_success_at = coalesce(excluded.last_success_at, %2$s.last_success_at),
                        last_failure_at = coalesce(excluded.last_failure_at, %2$s.last_failure_at),
                        runs = %2$s.runs + 1,
                        failures = %2$s.failures + excluded.failures,
                        updated_at = now()
                    """.formatted(table, "job_runs"),
                    truncate(job, 120), Timestamp.from(startedAt), Timestamp.from(finishedAt), took.toMillis(), ok ? "OK" : "FAILED", message,
                    ok ? Timestamp.from(finishedAt) : null, ok ? null : Timestamp.from(finishedAt), ok ? 0 : 1);
        } catch (Exception e) {
            log.warn("Couldn't record the run of {}: {}", job, e.getMessage());
        }
    }

    /** Runs something that isn't a @Scheduled method (a startup sync, a manual trigger) and records it the same way. */
    public void run(String job, Runnable work) {
        Instant start = Instant.now();
        long t0 = System.nanoTime();
        Throwable error = null;
        try {
            work.run();
        } catch (RuntimeException | Error e) {
            error = e;
            throw e;
        } finally {
            record(job, start, Duration.ofNanos(System.nanoTime() - t0), error);
        }
    }

    public List<JobRun> all() {
        return jdbc.query("select job_name, last_started_at, last_finished_at, last_duration_ms, last_status, last_error, last_success_at,"
                        + " last_failure_at, runs, failures from " + table + " order by job_name",
                (rs, i) -> new JobRun(rs.getString(1), instant(rs.getTimestamp(2)), instant(rs.getTimestamp(3)), rs.getLong(4),
                        rs.getString(5), rs.getString(6), instant(rs.getTimestamp(7)), instant(rs.getTimestamp(8)), rs.getLong(9),
                        rs.getLong(10)));
    }

    public record JobRun(String job, Instant lastStartedAt, Instant lastFinishedAt, long lastDurationMs, String lastStatus,
                         String lastError, Instant lastSuccessAt, Instant lastFailureAt, long runs, long failures) {
    }

    private static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
