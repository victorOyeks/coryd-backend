package org.coryd.notification.onboarding;

import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.coryd.common.enums.OpsPermission;
import org.coryd.common.enums.OpsRole;
import org.coryd.common.exception.BadRequestException;
import org.coryd.common.response.ApiResponse;
import org.coryd.common.security.CoRydUserDetails;
import org.coryd.notification.broadcast.BroadcastProperties;
import org.coryd.notification.broadcast.OpsAdmin;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * Running the daily jobs by hand from the Ops Console - ops-service only, with the super admin's own token. dryRun=true
 * (the default) lists who would be reminded / what the digest would say and sends nothing.
 */
@Hidden
@RestController
@RequestMapping("/internal/ops/notifications")
@RequiredArgsConstructor
public class InternalOpsNotificationJobsController {

    private final OnboardingReminderService reminders;
    private final VerificationDigestService digest;
    private final NotificationJobSettings settings;
    private final BroadcastProperties quietHours;
    private final DailyJobRuns runs;

    @PostMapping("/onboarding-reminders/run")
    public ApiResponse<OnboardingReminderService.Run> onboardingReminders(@AuthenticationPrincipal CoRydUserDetails principal,
                                                                          @RequestParam(defaultValue = "true") boolean dryRun) {
        OpsAdmin admin = superAdmin(principal);
        if (!dryRun) {
            if (!settings.remindersEnabled()) {
                throw new BadRequestException("Onboarding reminders are switched off (Settings: onboarding.reminders-enabled) - "
                        + "switch them on first, or use dryRun=true to see who would be reminded");
            }
            if (quietHours.isQuiet(Instant.now())) {
                throw new BadRequestException("It's quiet hours - reminders are pushes and wait until they end. Use dryRun=true to see who would be reminded");
            }
        }
        OnboardingReminderService.Run run = reminders.run(dryRun, JobActor.admin(admin.id(), admin.name()));
        return ApiResponse.ok(dryRun ? "Dry run - nothing sent" : run.reminded() + " reminded", run);
    }

    @PostMapping("/verification-digest/run")
    public ApiResponse<VerificationDigestService.Run> verificationDigest(@AuthenticationPrincipal CoRydUserDetails principal,
                                                                         @RequestParam(defaultValue = "true") boolean dryRun) {
        OpsAdmin admin = superAdmin(principal);
        VerificationDigestService.Run run = digest.run(dryRun, JobActor.admin(admin.id(), admin.name()));
        if (run.sent()) {
            runs.recordManual(VerificationDigestService.JOB, run.at().atZone(OnboardingReminderPlanner.LAGOS).toLocalDate(), run.at(),
                    run.recipients().size(), (int) run.counts().total());
        }
        String message = dryRun ? "Dry run - nothing sent" : run.sent() ? "Digest sent to " + run.delivered() + " of "
                + run.recipients().size() + " recipients" : "Not sent: " + run.notSentBecause();
        return ApiResponse.ok(message, run);
    }

    /** An ops admin whose role may manage the platform - super admins (checked here as well as in ops-service). */
    static OpsAdmin superAdmin(CoRydUserDetails principal) {
        OpsAdmin admin = OpsAdmin.of(principal);
        OpsRole role;
        try {
            role = OpsRole.effective(OpsRole.parse(principal.getOpsRole()));
        } catch (IllegalArgumentException e) {
            throw new AccessDeniedException("Unknown ops role");
        }
        if (!role.can(OpsPermission.PLATFORM_MANAGE)) {
            throw new AccessDeniedException("Your ops role (" + role + ") can't " + OpsPermission.PLATFORM_MANAGE.refusal());
        }
        return admin;
    }
}
