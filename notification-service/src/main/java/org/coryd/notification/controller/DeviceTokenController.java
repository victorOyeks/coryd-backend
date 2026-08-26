package org.coryd.notification.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.coryd.notification.dto.DeviceTokenRequest;
import org.coryd.notification.entity.DeviceToken;
import org.coryd.notification.service.DeviceTokenService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/device-tokens")
@RequiredArgsConstructor
public class DeviceTokenController {

    private final DeviceTokenService deviceTokenService;

    @PostMapping("/users/{userId}")
    public ApiResponse<DeviceToken> register(@PathVariable String userId, @Valid @RequestBody DeviceTokenRequest request) {
        return ApiResponse.ok(deviceTokenService.register(userId, request.fcmToken()));
    }

    @DeleteMapping("/users/{userId}")
    public ApiResponse<Void> remove(@PathVariable String userId, @RequestParam String fcmToken) {
        deviceTokenService.remove(userId, fcmToken);
        return ApiResponse.ok(null);
    }
}
