package org.coryd.ops.controller;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.coryd.common.security.CoRydUserDetails;
import org.coryd.ops.client.IdentityServiceClient;
import org.coryd.ops.dto.AccountEditResult;
import org.coryd.ops.dto.EditDetailsRequest;
import org.coryd.ops.dto.SuspendUserRequest;
import org.coryd.ops.service.AccountOpsService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Account actions on any rider or driver - see docs/ops-console/accounts.md. Suspending or signing out ends every session
 * on every service within seconds; a suspended account can't sign in, post or book, and its upcoming trips and seats are
 * cancelled with refunds. Every action takes a reason and is kept in the account history.
 */
@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserController {

    private final IdentityServiceClient identityServiceClient;
    private final AccountOpsService accountOpsService;

    @PostMapping("/{userId}/suspend")
    public ApiResponse<Void> suspend(@AuthenticationPrincipal CoRydUserDetails admin, @PathVariable String userId,
                                     @Valid @RequestBody SuspendUserRequest request) {
        accountOpsService.suspend(userId, request.reason(), admin);
        return ApiResponse.ok("Suspended", null);
    }

    /** The reason is required. Sessions ended by the suspension stay ended - the person signs in again. */
    @PostMapping("/{userId}/reinstate")
    public ApiResponse<Void> reinstate(@AuthenticationPrincipal CoRydUserDetails admin, @PathVariable String userId,
                                       @Valid @RequestBody SuspendUserRequest request) {
        accountOpsService.reinstate(userId, request.reason(), admin);
        return ApiResponse.ok("Reinstated", null);
    }

    /** Every session on every device ends within seconds; the person is told and signs in again. */
    @PostMapping("/{userId}/sign-out-everywhere")
    public ApiResponse<Void> signOutEverywhere(@AuthenticationPrincipal CoRydUserDetails admin, @PathVariable String userId,
                                               @Valid @RequestBody SuspendUserRequest request) {
        accountOpsService.signOutEverywhere(userId, request.reason(), admin);
        return ApiResponse.ok("Signed out everywhere", null);
    }

    /** First name, last name, email - send only what changes, with a reason. A phone number can't be changed here (400). */
    @PatchMapping("/{userId}/details")
    public ApiResponse<AccountEditResult> editDetails(@AuthenticationPrincipal CoRydUserDetails admin, @PathVariable String userId,
                                                      @Valid @RequestBody EditDetailsRequest request) {
        return ApiResponse.ok("Details updated", accountOpsService.editDetails(userId, request, admin));
    }

    /** Every ops action on the account, newest first. */
    @GetMapping("/{userId}/account-history")
    public ApiResponse<JsonNode> accountHistory(@PathVariable String userId, @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(accountOpsService.history(userId, page, size));
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
}
