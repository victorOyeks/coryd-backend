package org.coryd.identity.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.coryd.identity.dto.BetaSignupRequest;
import org.coryd.identity.service.BetaSignupService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public - the website's "Join the CoRyd beta" form. No account needed. */
@RestController
@RequestMapping("/beta-signups")
@RequiredArgsConstructor
public class BetaSignupController {

    private final BetaSignupService betaSignupService;

    @PostMapping
    public ApiResponse<Void> join(@Valid @RequestBody BetaSignupRequest request) {
        betaSignupService.join(request);
        return ApiResponse.ok("You're on the list - we'll send your invite on WhatsApp.", null);
    }
}
