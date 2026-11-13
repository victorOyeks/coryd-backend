package org.coryd.trip.jobs;

import org.coryd.common.jobs.JobRunRecorder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** trip.job_runs (coryd:131): one row per job, the last run's outcome, totals that only add up; a failed run keeps the last success. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
class JobRunRecorderIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            org.testcontainers.utility.DockerImageName.parse("postgis/postgis:16-3.4-alpine").asCompatibleSubstituteFor("postgres"));

    @Autowired JdbcTemplate jdbc;

    @Test
    void runsAreUpsertedPerJob() {
        jdbc.update("delete from trip.job_runs");
        JobRunRecorder recorder = new JobRunRecorder(jdbc, "trip");
        Instant t0 = Instant.parse("2026-11-13T10:00:00Z");
        recorder.record("RouteExpiryJob.run", t0, Duration.ofMillis(120), null);
        recorder.record("RouteExpiryJob.run", t0.plusSeconds(300), Duration.ofMillis(80), new IllegalStateException("db down"));
        assertThatThrownBy(() -> recorder.run("SosRealertJob.run", () -> {
            throw new IllegalArgumentException("bad");
        })).isInstanceOf(IllegalArgumentException.class);

        assertThat(recorder.all()).hasSize(2);
        JobRunRecorder.JobRun expiry = recorder.all().stream().filter(r -> r.job().equals("RouteExpiryJob.run")).findFirst().orElseThrow();
        assertThat(expiry.runs()).isEqualTo(2);
        assertThat(expiry.failures()).isEqualTo(1);
        assertThat(expiry.lastStatus()).isEqualTo("FAILED");
        assertThat(expiry.lastError()).isEqualTo("IllegalStateException: db down");
        assertThat(expiry.lastDurationMs()).isEqualTo(80);
        assertThat(expiry.lastSuccessAt()).isEqualTo(t0.plusMillis(120));
        assertThat(expiry.lastStartedAt()).isEqualTo(t0.plusSeconds(300));
        assertThatThrownBy(() -> new JobRunRecorder(jdbc, "trip; drop table x")).isInstanceOf(IllegalArgumentException.class);
    }
}
