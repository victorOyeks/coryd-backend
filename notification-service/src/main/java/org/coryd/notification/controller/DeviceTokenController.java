package org.coryd.notification.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.coryd.common.security.CoRydUserDetails;
import org.coryd.notification.dto.DeviceTokenRequest;
import org.coryd.notification.entity.DeviceToken;
import org.coryd.notification.security.NotificationOwnership;
import org.coryd.notification.service.DeviceTokenService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The phone registers its FCM token for the signed-in user, and removes it on sign-out. A token can only ever be registered
 * under the caller's own userId (NotificationOwnership), so a token that moves from one account to another moves to whoever
 * is signed in on that phone now - never onto someone else's account.
 */
@RestController
@RequestMapping("/device-tokens")
@RequiredArgsConstructor
public class DeviceTokenController {

    private final DeviceTokenService deviceTokenService;
    private final NotificationOwnership ownership;

    @PostMapping("/users/{userId}")
    public ApiResponse<DeviceToken> register(@PathVariable String userId, @Valid @RequestBody DeviceTokenRequest request,
                                             @AuthenticationPrincipal CoRydUserDetails caller, HttpServletRequest httpRequest) {
        ownership.requireSelf(userId, caller, httpRequest);
        return ApiResponse.ok(deviceTokenService.register(userId, request.fcmToken()));
    }

    @DeleteMapping("/users/{userId}")
    public ApiResponse<Void> remove(@PathVariable String userId, @RequestParam String fcmToken,
                                    @AuthenticationPrincipal CoRydUserDetails caller, HttpServletRequest httpRequest) {
        ownership.requireSelf(userId, caller, httpRequest);
        deviceTokenService.remove(userId, fcmToken);
        return ApiResponse.ok(null);
    }
}
