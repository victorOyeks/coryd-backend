package org.coryd.identity.controller;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.coryd.identity.dto.UpdateFullNameRequest;
import org.coryd.identity.dto.UserContactResponse;
import org.coryd.identity.service.UserService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Minimal cross-service user lookup/sync (trip-service's Call-driver button, profile-service's name push) - deliberately exposes only phone/fullName, nothing auth-related. Service-to-service only, gated by InternalApiKeyFilter via the /internal/** prefix and hidden from Swagger. */
@Hidden
@RestController
@RequestMapping("/internal/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping("/{userId}/contact")
    public ApiResponse<UserContactResponse> contact(@PathVariable String userId) {
        return ApiResponse.ok(userService.getContact(userId));
    }

    @PutMapping("/{userId}/full-name")
    public ApiResponse<Void> updateFullName(@PathVariable String userId,
                                            @Valid @RequestBody UpdateFullNameRequest request) {
        userService.updateFullName(userId, request.fullName());
        return ApiResponse.ok("Updated", null);
    }
}
