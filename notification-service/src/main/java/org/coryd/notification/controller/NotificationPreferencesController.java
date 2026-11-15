package org.coryd.notification.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.coryd.common.security.CoRydUserDetails;
import org.coryd.notification.entity.NotificationPreferences;
import org.coryd.notification.repository.NotificationPreferencesRepository;
import org.coryd.notification.security.NotificationOwnership;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** A user's notification switches - only the user themself (or another service) may read or change them: NotificationOwnership. */
@RestController
@RequestMapping("/preferences")
@RequiredArgsConstructor
public class NotificationPreferencesController {

    private final NotificationPreferencesRepository preferencesRepository;
    private final NotificationOwnership ownership;

    /** The current switches, without changing anything - the defaults (both on) when the user has never set them; nothing is stored. */
    @GetMapping("/users/{userId}")
    public ApiResponse<NotificationPreferences> getPreferences(@PathVariable String userId,
                                                               @AuthenticationPrincipal CoRydUserDetails caller,
                                                               HttpServletRequest request) {
        ownership.requireSelf(userId, caller, request);
        return ApiResponse.ok(preferencesRepository.findByUserId(userId)
                .orElseGet(() -> NotificationPreferences.builder().userId(userId).build()));
    }

    /** Either flag may be sent alone; one that isn't sent keeps its current value. */
    @PutMapping("/users/{userId}")
    public ApiResponse<NotificationPreferences> setPreferences(@PathVariable String userId,
                                                               @RequestParam(required = false) Boolean promotionalOptIn,
                                                               @RequestParam(required = false) Boolean whatsappOptIn,
                                                               @AuthenticationPrincipal CoRydUserDetails caller,
                                                               HttpServletRequest request) {
        ownership.requireSelf(userId, caller, request);
        NotificationPreferences preferences = preferencesRepository.findByUserId(userId)
                .orElseGet(() -> NotificationPreferences.builder().userId(userId).build());
        if (promotionalOptIn != null) {
            preferences.setPromotionalOptIn(promotionalOptIn);
        }
        if (whatsappOptIn != null) {
            preferences.setWhatsappOptIn(whatsappOptIn);
        }
        return ApiResponse.ok(preferencesRepository.save(preferences));
    }
}
