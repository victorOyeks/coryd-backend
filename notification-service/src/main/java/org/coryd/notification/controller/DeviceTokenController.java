package org.coryd.notification.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.coryd.notification.dto.DeviceTokenRequest;
import org.coryd.notification.entity.DeviceToken;
import org.coryd.notification.repository.DeviceTokenRepository;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/device-tokens")
@RequiredArgsConstructor
public class DeviceTokenController {

    private final DeviceTokenRepository deviceTokenRepository;

    @PostMapping("/users/{userId}")
    public ApiResponse<DeviceToken> register(@PathVariable String userId, @Valid @RequestBody DeviceTokenRequest request) {
        DeviceToken token = DeviceToken.builder()
                .userId(userId)
                .fcmToken(request.fcmToken())
                .build();
        return ApiResponse.ok(deviceTokenRepository.save(token));
    }
}
