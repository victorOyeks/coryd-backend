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

    @PutMapping("/users/{userId}")
    public ApiResponse<NotificationPreferences> setPromotionalOptIn(@PathVariable String userId,
                                                                      @RequestParam boolean promotionalOptIn) {
        NotificationPreferences preferences = preferencesRepository.findByUserId(userId)
                .orElseGet(() -> NotificationPreferences.builder().userId(userId).build());
        preferences.setPromotionalOptIn(promotionalOptIn);
        return ApiResponse.ok(preferencesRepository.save(preferences));
    }
}
