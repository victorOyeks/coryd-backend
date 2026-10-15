package org.coryd.identity.controller.internal;

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

/** Minimal cross-service user lookup/sync (trip-service's Call-driver button, profile-service's name push) - deliberately exposes only phone/name, nothing auth-related. Service-to-service only, gated by InternalApiKeyFilter via the /internal/** prefix and hidden from Swagger. */
@Hidden
@RestController
@RequestMapping("/internal/users")
@RequiredArgsConstructor
public class InternalUserController {

    private final UserService userService;
    private final SuspensionService suspensionService;

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
        suspensionService.suspend(userId, request.reason(), request.opsUserId());
        return ApiResponse.ok("Suspended", null);
    }

    @PostMapping("/{userId}/reinstate")
    public ApiResponse<Void> reinstate(@PathVariable String userId, @RequestParam(required = false) String opsUserId) {
        suspensionService.reinstate(userId, opsUserId);
        return ApiResponse.ok("Reinstated", null);
    }

    @PutMapping("/{userId}/name")
    public ApiResponse<Void> updateName(@PathVariable String userId,
                                        @Valid @RequestBody UpdateNameRequest request) {
        userService.updateName(userId, request.firstName(), request.lastName());
        return ApiResponse.ok("Updated", null);
    }
}
