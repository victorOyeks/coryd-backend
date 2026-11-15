package org.coryd.notification.onboarding;

import lombok.extern.slf4j.Slf4j;
import org.coryd.notification.broadcast.BroadcastProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZonedDateTime;

/**
 * Starts the two daily jobs (docs/ops-console/onboarding-reminders.md). Checks every minute rather than on a fixed cron,
 * because the time is a platform setting ops can change: a job runs at the first check at or after its time on a Lagos
 * day it hasn't run yet (so a service that was down at 08:00 catches up when it's back). Onboarding reminders are pushes,
 * so they also wait for the broadcast quiet hours to end. DailyJobRuns makes sure one instance runs each day's job once.
 */
@Slf4j
@Component
public class NotificationDailyJobs {

    private final NotificationJobSettings settings;
    private final OnboardingReminderService reminders;
    private final VerificationDigestService digest;
    private final DailyJobRuns runs;
    private final BroadcastProperties quietHours;
    private volatile Clock clock = Clock.systemUTC();

    public NotificationDailyJobs(NotificationJobSettings settings, OnboardingReminderService reminders, VerificationDigestService digest,
                                 DailyJobRuns runs, BroadcastProperties quietHours) {
        this.settings = settings;
        this.reminders = reminders;
        this.digest = digest;
        this.runs = runs;
        this.quietHours = quietHours;
    }

    /** Tests only. */
    public void useClock(Clock clock) {
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${notification-jobs.check-ms:60000}", initialDelayString = "${notification-jobs.initial-delay-ms:30000}")
    public void onboardingReminders() {
        Instant now = clock.instant();
        if (!settings.remindersEnabled() || !timeHasCome(now, settings.reminderTime()) || quietHours.isQuiet(now)) {
            return;
        }
        ZonedDateTime lagos = now.atZone(OnboardingReminderPlanner.LAGOS);
        if (!runs.claim(OnboardingReminderService.JOB, lagos.toLocalDate(), now)) {
            return;
        }
        try {
            OnboardingReminderService.Run run = reminders.run(false, JobActor.SCHEDULER);
            runs.done(OnboardingReminderService.JOB, lagos.toLocalDate(), clock.instant(), run.reminded(), run.considered());
        } catch (RuntimeException e) {
            runs.failed(OnboardingReminderService.JOB, lagos.toLocalDate(), clock.instant(), e.getClass().getSimpleName() + ": " + e.getMessage());
            throw e;
        }
    }

    @Scheduled(fixedDelayString = "${notification-jobs.check-ms:60000}", initialDelayString = "${notification-jobs.initial-delay-ms:30000}")
    public void verificationDigest() {
        Instant now = clock.instant();
        if (!settings.digestEnabled() || !timeHasCome(now, settings.digestTime())) {
            return;
        }
        ZonedDateTime lagos = now.atZone(OnboardingReminderPlanner.LAGOS);
        if (!runs.claim(VerificationDigestService.JOB, lagos.toLocalDate(), now)) {
            return;
        }
        try {
            VerificationDigestService.Run run = digest.run(false, JobActor.SCHEDULER);
            runs.done(VerificationDigestService.JOB, lagos.toLocalDate(), clock.instant(), run.sent() ? run.recipients().size() : 0,
                    (int) run.counts().total());
        } catch (RuntimeException e) {
            runs.failed(VerificationDigestService.JOB, lagos.toLocalDate(), clock.instant(), e.getClass().getSimpleName() + ": " + e.getMessage());
            throw e;
        }
    }

    static boolean timeHasCome(Instant now, LocalTime at) {
        return !now.atZone(OnboardingReminderPlanner.LAGOS).toLocalTime().isBefore(at);
    }
}
