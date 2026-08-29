package org.coryd.identity.controller;

import lombok.RequiredArgsConstructor;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.common.response.ApiResponse;
import org.coryd.identity.dto.UserContactResponse;
import org.coryd.identity.entity.User;
import org.coryd.identity.repository.UserRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Minimal cross-service contact lookup (e.g. trip-service's Call-driver button) - deliberately exposes only phone/fullName, nothing auth-related. Service-to-service only - never called by a mobile client directly, gated by InternalApiKeyFilter via the /internal/** prefix and hidden from Swagger. */
@RestController
@RequestMapping("/internal/users")
@RequiredArgsConstructor
public class UserController {

    private final UserRepository userRepository;

    @GetMapping("/{userId}/contact")
    public ApiResponse<UserContactResponse> contact(@PathVariable String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        return ApiResponse.ok(new UserContactResponse(user.getId(), user.getFullName(), user.getPhone()));
    }
}
