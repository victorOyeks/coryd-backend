package org.coryd.profile.controller.internal;

import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.coryd.profile.service.OnboardingStatusService;
import org.coryd.profile.service.VerificationWaitingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * notification-service's daily jobs (docs/ops-console/onboarding-reminders.md) - service-to-service only, behind
 * InternalApiKeyFilter: where each rider and driver stands in signing up, and who is waiting on a verification officer.
 */
@Hidden
@RestController
@RequestMapping("/internal/onboarding")
@RequiredArgsConstructor
public class InternalOnboardingController {

    private final OnboardingStatusService onboardingStatusService;
    private final VerificationWaitingService verificationWaitingService;

    public record StatusRequest(List<OnboardingStatusService.Person> people) {
    }

    /** At most 500 people per call. */
    @PostMapping("/status")
    public ApiResponse<List<OnboardingStatusService.Status>> status(@RequestBody StatusRequest request) {
        return ApiResponse.ok(onboardingStatusService.status(request == null ? List.of() : request.people()));
    }

    @GetMapping("/verification-waiting")
    public ApiResponse<VerificationWaitingService.Waiting> verificationWaiting() {
        return ApiResponse.ok(verificationWaitingService.waiting());
    }
}
