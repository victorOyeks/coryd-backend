package org.coryd.common.jobs;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.support.ScheduledTaskObservationContext;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Records every @Scheduled run without touching the jobs: Spring observes each scheduled invocation (Boot's
 * ScheduledTasksObservabilityAutoConfiguration), and this handler turns the observation into a job_runs row.
 */
@Component
@ConditionalOnProperty(name = "coryd.job-runs.schema")
public class ScheduledJobObservationHandler implements ObservationHandler<ScheduledTaskObservationContext> {

    private static final String STARTED = "coryd.job.started";
    private static final String NANOS = "coryd.job.nanos";

    private final JobRunRecorder recorder;

    public ScheduledJobObservationHandler(JobRunRecorder recorder) {
        this.recorder = recorder;
    }

    @Override
    public void onStart(ScheduledTaskObservationContext context) {
        context.put(STARTED, Instant.now());
        context.put(NANOS, System.nanoTime());
    }

    @Override
    public void onStop(ScheduledTaskObservationContext context) {
        Instant started = context.getOrDefault(STARTED, Instant.now());
        Long nanos = context.get(NANOS);
        Duration took = nanos == null ? Duration.ZERO : Duration.ofNanos(System.nanoTime() - nanos);
        recorder.record(jobName(context), started, took, context.getError());
    }

    @Override
    public boolean supportsContext(Observation.Context context) {
        return context instanceof ScheduledTaskObservationContext;
    }

    static String jobName(ScheduledTaskObservationContext context) {
        return context.getTargetClass().getSimpleName().split("\\$\\$")[0] + "." + context.getMethod().getName();
    }
}
