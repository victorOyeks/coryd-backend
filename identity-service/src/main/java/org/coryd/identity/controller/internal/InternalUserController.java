package org.coryd.identity.controller.internal;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.coryd.identity.service.AccountDeletionService;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.coryd.identity.dto.SuspendRequest;
import org.coryd.identity.dto.UpdateNameRequest;
import org.coryd.identity.dto.UserContactResponse;
import org.coryd.identity.service.SuspensionService;
import org.coryd.identity.service.UserService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Cross-service user lookup/sync, and the ops console's account actions (always through ops-service) - (trip-service's Call-driver button, profile-service's name push) - deliberately exposes only phone/name, nothing auth-related. Service-to-service only, gated by InternalApiKeyFilter via the /internal/** prefix and hidden from Swagger. */
@Hidden
@RestController
@RequestMapping("/internal/users")
@RequiredArgsConstructor
public class InternalUserController {

    private final UserService userService;
    private final SuspensionService suspensionService;
    private final AccountDeletionService accountDeletionService;
    private final org.coryd.identity.service.SessionRevocationService sessionRevocationService;
    private final org.coryd.identity.service.AccountActionService accountActionService;
    private final org.coryd.identity.service.AccountOverviewService accountOverviewService;

    @GetMapping("/{userId}/contact")
    public ApiResponse<UserContactResponse> contact(@PathVariable String userId) {
        return ApiResponse.ok(userService.getContact(userId));
    }

    @GetMapping("/contacts")
    public ApiResponse<List<UserContactResponse>> contacts(@RequestParam List<String> userIds) {
        return ApiResponse.ok(userService.getContacts(userIds));
    }

    /** Ops Console "Suspend" - through ops-service, which checks the caller is OPS_ADMIN. */
    @PostMapping("/{userId}/suspend")
    public ApiResponse<Void> suspend(@PathVariable String userId, @Valid @RequestBody SuspendRequest request) {
        suspensionService.suspend(userId, request.reason(), request.opsUserId(), request.opsUserName());
        return ApiResponse.ok("Suspended", null);
    }

    /** Ops Console "Sign out everywhere": every session on every device ends now. */
    @PostMapping("/{userId}/sign-out-everywhere")
    public ApiResponse<Void> signOutEverywhere(@PathVariable String userId,
                                               @Valid @RequestBody org.coryd.identity.dto.AccountActionRequest request) {
        suspensionService.signOutEverywhere(userId, request.reason(), request.opsUserId(), request.opsUserName());
        return ApiResponse.ok("Signed out everywhere", null);
    }

    /** Every user some tokens must be refused for - every service's JwtFilter loads this at startup (TokenRevocationBootstrap). */
    @GetMapping("/token-revocations")
    public ApiResponse<List<org.coryd.common.kafka.events.UserTokensRevokedEvent>> tokenRevocations() {
        return ApiResponse.ok(sessionRevocationService.fetchAll());
    }

    /** The account half of the ops console's driver and rider profiles. */
    @GetMapping("/{userId}/account")
    public ApiResponse<org.coryd.identity.dto.AccountOverview> account(@PathVariable String userId) {
        return ApiResponse.ok(accountOverviewService.overview(userId));
    }

    /** The account history, newest first. */
    @GetMapping("/{userId}/account-actions")
    public ApiResponse<org.coryd.common.response.PagedResponse<org.coryd.identity.dto.AccountActionView>> accountActions(
            @PathVariable String userId, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(accountActionService.history(userId, page, size));
    }

    /** An edit or reset ops-service made in another service, added to the account history. */
    @PostMapping("/{userId}/account-actions")
    public ApiResponse<org.coryd.identity.dto.AccountActionView> recordAccountAction(
            @PathVariable String userId, @Valid @RequestBody org.coryd.identity.dto.RecordAccountActionRequest request) {
        return ApiResponse.ok("Recorded", accountActionService.recordFromOps(userId, request));
    }

    /** Ops Console "Delete account" - through ops-service, which checks the caller is OPS_ADMIN. */
    @DeleteMapping("/{userId}")
    public ApiResponse<Void> delete(@PathVariable String userId, @RequestParam String opsUserId) {
        accountDeletionService.deleteByOps(userId, opsUserId);
        return ApiResponse.ok("Account deleted", null);
    }

    /** The reason is required - it's kept in the account history next to the suspension it ends. */
    @PostMapping("/{userId}/reinstate")
    public ApiResponse<Void> reinstate(@PathVariable String userId,
                                       @Valid @RequestBody org.coryd.identity.dto.AccountActionRequest request) {
        suspensionService.reinstate(userId, request.reason(), request.opsUserId(), request.opsUserName());
        return ApiResponse.ok("Reinstated", null);
    }

    @PutMapping("/{userId}/name")
    public ApiResponse<Void> updateName(@PathVariable String userId,
                                        @Valid @RequestBody UpdateNameRequest request) {
        userService.updateName(userId, request.firstName(), request.lastName());
        return ApiResponse.ok("Updated", null);
    }
}
