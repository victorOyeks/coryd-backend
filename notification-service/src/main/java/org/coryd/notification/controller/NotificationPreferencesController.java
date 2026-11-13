package org.coryd.notification.controller;

import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.coryd.notification.entity.NotificationPreferences;
import org.coryd.notification.repository.NotificationPreferencesRepository;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/preferences")
@RequiredArgsConstructor
public class NotificationPreferencesController {

    private final NotificationPreferencesRepository preferencesRepository;

    /** Either flag may be sent alone; one that isn't sent keeps its current value. */
    @PutMapping("/users/{userId}")
    public ApiResponse<NotificationPreferences> setPreferences(@PathVariable String userId,
                                                               @RequestParam(required = false) Boolean promotionalOptIn,
                                                               @RequestParam(required = false) Boolean whatsappOptIn) {
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
