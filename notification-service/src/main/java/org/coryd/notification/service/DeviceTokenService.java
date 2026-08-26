package org.coryd.notification.service;

import lombok.RequiredArgsConstructor;
import org.coryd.notification.entity.DeviceToken;
import org.coryd.notification.repository.DeviceTokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DeviceTokenService {

    private final DeviceTokenRepository deviceTokenRepository;

    /** fcmToken is globally unique - re-registering the same token (app reopened, token unchanged)
     * must update the existing row instead of inserting a duplicate. Repointing userId also covers
     * a different account logging into the same device without stale-push carrying over. */
    @Transactional
    public DeviceToken register(String userId, String fcmToken) {
        return deviceTokenRepository.findByFcmToken(fcmToken)
                .map(existing -> {
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
