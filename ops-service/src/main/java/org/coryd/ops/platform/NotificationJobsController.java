package org.coryd.ops.platform;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.springframework.http.HttpMethod;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The daily notification jobs, run by hand (docs/ops-console/onboarding-reminders.md) - super admins only (OpsAccessPolicy:
 * PLATFORM_MANAGE). dryRun defaults to true: who would be reminded / what the digest would say, nothing sent.
 */
@RestController
@RequestMapping("/notifications")
@RequiredArgsConstructor
public class NotificationJobsController {

    private static final String BASE = "/internal/ops/notifications";

    private final PlatformClient client;

    @PostMapping("/onboarding-reminders/run")
    public ApiResponse<JsonNode> onboardingReminders(@RequestParam(defaultValue = "true") boolean dryRun) {
        return SettingsController.relay(client.send(PlatformClient.Service.NOTIFICATION, HttpMethod.POST, BASE + "/onboarding-reminders/run",
                Params.of().put("dryRun", dryRun).build(), null));
    }

    @PostMapping("/verification-digest/run")
    public ApiResponse<JsonNode> verificationDigest(@RequestParam(defaultValue = "true") boolean dryRun) {
        return SettingsController.relay(client.send(PlatformClient.Service.NOTIFICATION, HttpMethod.POST, BASE + "/verification-digest/run",
                Params.of().put("dryRun", dryRun).build(), null));
    }
}
