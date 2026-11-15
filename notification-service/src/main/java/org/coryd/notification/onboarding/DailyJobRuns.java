package org.coryd.notification.onboarding;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Date;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;

/**
 * One row per daily job per Lagos day (notification.daily_job_runs). claim() is a single insert-or-take-over statement,
 * so only one instance runs a day's job: a FAILED run is retried (at most 3 tries), a RUNNING one left for 2 hours (a
 * crashed instance) is taken over; DONE is never run again.
 */
@Component
public class DailyJobRuns {

    static final int MAX_ATTEMPTS = 3;
    static final Duration STALE = Duration.ofHours(2);

    private final NamedParameterJdbcTemplate jdbc;

    public DailyJobRuns(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean claim(String job, LocalDate day, Instant now) {
        return jdbc.update("""
                INSERT INTO notification.daily_job_runs AS d (job_name, run_date, status, attempts, started_at, updated_at)
                VALUES (:job, :day, 'RUNNING', 1, :now, :now)
                ON CONFLICT (job_name, run_date) DO UPDATE SET status = 'RUNNING', attempts = d.attempts + 1, started_at = :now,
                    finished_at = NULL, error = NULL, updated_at = :now
                WHERE (d.status = 'FAILED' AND d.attempts < :max) OR (d.status = 'RUNNING' AND d.started_at < :stale)
                """, params(job, day, now).addValue("max", MAX_ATTEMPTS).addValue("stale", utc(now.minus(STALE)))) == 1;
    }

    public void done(String job, LocalDate day, Instant now, int recipients, int items) {
        jdbc.update("""
                UPDATE notification.daily_job_runs SET status = 'DONE', finished_at = :now, recipients = :recipients, items = :items,
                    updated_at = :now WHERE job_name = :job AND run_date = :day
                """, params(job, day, now).addValue("recipients", recipients).addValue("items", items));
    }

    public void failed(String job, LocalDate day, Instant now, String error) {
        jdbc.update("""
                UPDATE notification.daily_job_runs SET status = 'FAILED', finished_at = :now, error = :error, updated_at = :now
                WHERE job_name = :job AND run_date = :day
                """, params(job, day, now).addValue("error", error == null ? null : error.length() > 500 ? error.substring(0, 500) : error));
    }

    /** A run by hand that sent: the day's scheduled run is then not needed - recorded as DONE unless it already ran. */
    public void recordManual(String job, LocalDate day, Instant now, int recipients, int items) {
        jdbc.update("""
                INSERT INTO notification.daily_job_runs (job_name, run_date, status, attempts, started_at, finished_at, recipients, items, updated_at)
                VALUES (:job, :day, 'DONE', 1, :now, :now, :recipients, :items, :now)
                ON CONFLICT (job_name, run_date) DO NOTHING
                """, params(job, day, now).addValue("recipients", recipients).addValue("items", items));
    }

    public String status(String job, LocalDate day) {
        return jdbc.query("SELECT status FROM notification.daily_job_runs WHERE job_name = :job AND run_date = :day",
                params(job, day, Instant.EPOCH), rs -> rs.next() ? rs.getString(1) : null);
    }

    private static MapSqlParameterSource params(String job, LocalDate day, Instant now) {
        return new MapSqlParameterSource().addValue("job", job).addValue("day", Date.valueOf(day)).addValue("now", utc(now));
    }

    /** As Hibernate writes Instants to TIMESTAMP columns: UTC wall-clock time. */
    static java.time.LocalDateTime utc(Instant instant) {
        return instant == null ? null : java.time.LocalDateTime.ofInstant(instant, java.time.ZoneOffset.UTC);
    }
}
