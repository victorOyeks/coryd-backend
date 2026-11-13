package org.coryd.common.jobs;

import io.swagger.v3.oas.annotations.Hidden;
import org.coryd.common.response.ApiResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.FixedRateTask;
import org.springframework.scheduling.config.IntervalTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.scheduling.config.Task;
import org.springframework.scheduling.support.ScheduledMethodRunnable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Service-to-service only (ops-service's system health screen) - {@code /internal/**} is key-gated in every service. Every
 * scheduled job this service has, with its schedule and last run (a job that never ran is listed with no run), and the
 * failure counts of the providers it calls over the last {@code hours}.
 */
@Hidden
@RestController
@ConditionalOnProperty(name = "coryd.job-runs.schema")
public class InternalSystemStatusController {

    private final JobRunRecorder recorder;
    private final List<ScheduledTaskHolder> holders;
    private final List<IntegrationHealthSource> integrations;
    private final String service;

    public InternalSystemStatusController(JobRunRecorder recorder, List<ScheduledTaskHolder> holders,
                                          List<IntegrationHealthSource> integrations,
                                          @Value("${spring.application.name:coryd}") String service) {
        this.recorder = recorder;
        this.holders = holders;
        this.integrations = integrations;
        this.service = service;
    }

    @GetMapping("/internal/system/status")
    public ApiResponse<Status> status(@RequestParam(defaultValue = "24") int hours) {
        int window = Math.max(1, Math.min(hours, 24 * 30));
        Map<String, Job> jobs = new LinkedHashMap<>();
        for (ScheduledTaskHolder holder : holders) {
            for (ScheduledTask scheduled : holder.getScheduledTasks()) {
                Task task = scheduled.getTask();
                if (task.getRunnable() instanceof ScheduledMethodRunnable method) {
                    String name = method.getTarget().getClass().getSimpleName().split("\\$\\$")[0] + "." + method.getMethod().getName();
                    jobs.put(name, new Job(name, schedule(task), null));
                }
            }
        }
        for (JobRunRecorder.JobRun run : recorder.all()) {
            Job known = jobs.get(run.job());
            jobs.put(run.job(), new Job(run.job(), known == null ? null : known.schedule(), run));
        }
        Instant since = Instant.now().minus(window, ChronoUnit.HOURS);
        List<IntegrationHealthSource.IntegrationHealth> health = new ArrayList<>();
        for (IntegrationHealthSource source : integrations) {
            try {
                health.addAll(source.integrations(since));
            } catch (Exception e) {
                health.add(new IntegrationHealthSource.IntegrationHealth(source.getClass().getSimpleName(), "status", null, 0, null,
                        "Couldn't count: " + e.getMessage()));
            }
        }
        return ApiResponse.ok(new Status(service, Instant.now(), window, new ArrayList<>(jobs.values()), health));
    }

    static String schedule(Task task) {
        if (task instanceof CronTask cron) {
            return "cron " + cron.getExpression();
        }
        if (task instanceof FixedDelayTask delay) {
            return "every " + delay.getIntervalDuration().toSeconds() + "s after the last run";
        }
        if (task instanceof FixedRateTask rate) {
            return "every " + rate.getIntervalDuration().toSeconds() + "s";
        }
        if (task instanceof IntervalTask interval) {
            return "every " + interval.getIntervalDuration().toSeconds() + "s";
        }
        return null;
    }

    public record Status(String service, Instant at, int windowHours, List<Job> jobs,
                         List<IntegrationHealthSource.IntegrationHealth> integrations) {
    }

    public record Job(String job, String schedule, JobRunRecorder.JobRun lastRun) {
    }
}
