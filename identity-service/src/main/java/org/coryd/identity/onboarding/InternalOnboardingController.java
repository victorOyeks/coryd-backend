package org.coryd.identity.onboarding;

import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** notification-service's onboarding reminders only - gated by InternalApiKeyFilter via the /internal/** prefix. */
@Hidden
@RestController
@RequestMapping("/internal/onboarding")
@RequiredArgsConstructor
public class InternalOnboardingController {

    private final OnboardingCandidatesService service;

    @GetMapping("/candidates")
    public ApiResponse<OnboardingCandidatesService.Page> candidates(@RequestParam(required = false) String after,
                                                                    @RequestParam(defaultValue = "500") int size) {
        return ApiResponse.ok(service.page(after, size));
    }
}
