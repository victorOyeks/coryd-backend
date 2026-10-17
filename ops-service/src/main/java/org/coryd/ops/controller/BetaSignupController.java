package org.coryd.ops.controller;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.coryd.common.security.CoRydUserDetails;
import org.coryd.ops.client.IdentityServiceClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The website's beta waitlist: who wants in, on which route and phone, and who has been invited.
 * Filters: status (WAITING | INVITED), willMostly (RIDE | DRIVE), device (IPHONE | ANDROID), search.
 */
@RestController
@RequestMapping("/beta-signups")
@RequiredArgsConstructor
public class BetaSignupController {

    private final IdentityServiceClient identityServiceClient;

    @GetMapping
    public ApiResponse<JsonNode> list(@RequestParam(required = false) String status,
                                      @RequestParam(required = false) String willMostly,
                                      @RequestParam(required = false) String device,
                                      @RequestParam(required = false) String search,
                                      @RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(identityServiceClient.betaSignups(status, willMostly, device, search, page, Math.min(size, 100)));
    }

    /** Totals for the header: total, riders/drivers, iPhone/Android, waiting/invited, and how many have joined the app. */
    @GetMapping("/summary")
    public ApiResponse<JsonNode> summary() {
        return ApiResponse.ok(identityServiceClient.betaSignupSummary());
    }

    /** After sending their WhatsApp invite (TestFlight / Play link), so nobody is invited twice. */
    @PostMapping("/{id}/invited")
    public ApiResponse<JsonNode> invited(@AuthenticationPrincipal CoRydUserDetails admin, @PathVariable String id) {
        return ApiResponse.ok(identityServiceClient.markBetaSignupInvited(id, admin.getUserId()));
    }
}
