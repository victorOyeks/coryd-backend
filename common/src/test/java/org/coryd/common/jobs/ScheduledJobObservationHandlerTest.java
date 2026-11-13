package org.coryd.common.jobs;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.support.ScheduledTaskObservationContext;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** A @Scheduled run becomes one job_runs record - OK, or FAILED with the error - named Class.method. */
class ScheduledJobObservationHandlerTest {

    static class RouteExpiryJob {
        public void run() {
        }
    }

    @Test
    void recordsEachRunWithItsOutcome() throws Exception {
        JobRunRecorder recorder = mock(JobRunRecorder.class);
        ScheduledJobObservationHandler handler = new ScheduledJobObservationHandler(recorder);
        ScheduledTaskObservationContext context = new ScheduledTaskObservationContext(new RouteExpiryJob(), RouteExpiryJob.class.getMethod("run"));
        assertThat(handler.supportsContext(context)).isTrue();

        handler.onStart(context);
        handler.onStop(context);
        verify(recorder).record(eq("RouteExpiryJob.run"), any(Instant.class), any(Duration.class), isNull());

        IllegalStateException boom = new IllegalStateException("db down");
        ScheduledTaskObservationContext failing = new ScheduledTaskObservationContext(new RouteExpiryJob(), RouteExpiryJob.class.getMethod("run"));
        handler.onStart(failing);
        failing.setError(boom);
        handler.onStop(failing);
        verify(recorder).record(eq("RouteExpiryJob.run"), any(Instant.class), any(Duration.class), same(boom));
    }
}
