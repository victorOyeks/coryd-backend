package org.coryd.notification.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.notification.entity.DeviceToken;
import org.coryd.notification.repository.DeviceTokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceTokenService {

    private final DeviceTokenRepository deviceTokenRepository;

    /** fcmToken is globally unique - re-registering the same token (app reopened, token unchanged)
     * must update the existing row instead of inserting a duplicate. Repointing userId also covers
     * a different account logging into the same device without stale-push carrying over. The controller only lets a
     * caller register under their own userId (NotificationOwnership), so the token moves to whoever is signed in on that
     * phone now, never onto someone else's account. */
    @Transactional
    public DeviceToken register(String userId, String fcmToken) {
        return deviceTokenRepository.findByFcmToken(fcmToken)
                .map(existing -> {
                    if (!userId.equals(existing.getUserId())) {
                        log.info("Device token {} moved from user {} to user {} (new sign-in on that device)",
                                existing.getId(), existing.getUserId(), userId);
                    }
                    existing.setUserId(userId);
                    return deviceTokenRepository.save(existing);
                })
                .orElseGet(() -> deviceTokenRepository.save(
                        DeviceToken.builder().userId(userId).fcmToken(fcmToken).build()));
    }

    @Transactional
    public void remove(String userId, String fcmToken) {
        deviceTokenRepository.deleteByUserIdAndFcmToken(userId, fcmToken);
    }
}
