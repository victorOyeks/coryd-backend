package org.coryd.ops.controller;

import org.springframework.web.bind.annotation.DeleteMapping;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.coryd.common.security.CoRydUserDetails;
import org.coryd.ops.client.IdentityServiceClient;
import org.coryd.ops.dto.SuspendUserRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Suspend, reinstate or delete any rider or driver. A suspended account can't sign in or refresh, can't post or
 * book, and its upcoming routes and seats are cancelled with refunds.
 */
@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserController {

    private final IdentityServiceClient identityServiceClient;

    @PostMapping("/{userId}/suspend")
    public ApiResponse<Void> suspend(@AuthenticationPrincipal CoRydUserDetails admin, @PathVariable String userId,
                                     @Valid @RequestBody SuspendUserRequest request) {
        identityServiceClient.suspend(userId, request.reason(), admin.getUserId());
        return ApiResponse.ok("Suspended", null);
    }

    /**
     * Delete the account for good: personal data erased in every service, past trips and payments kept
     * anonymised, upcoming trips cancelled and refunded. Refused while the person is on a trip or has money
     * still moving - the message says why. The phone number can then sign up again as a new account.
     */
    @DeleteMapping("/{userId}")
    public ApiResponse<Void> delete(@AuthenticationPrincipal CoRydUserDetails admin, @PathVariable String userId) {
        identityServiceClient.deleteAccount(userId, admin.getUserId());
        return ApiResponse.ok("Account deleted", null);
    }

    @PostMapping("/{userId}/reinstate")
    public ApiResponse<Void> reinstate(@AuthenticationPrincipal CoRydUserDetails admin, @PathVariable String userId) {
        identityServiceClient.reinstate(userId, admin.getUserId());
        return ApiResponse.ok("Reinstated", null);
    }
}
