package org.coryd.notification.repository;

import org.coryd.notification.entity.DeviceToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DeviceTokenRepository extends JpaRepository<DeviceToken, String> {
    List<DeviceToken> findByUserId(String userId);

    Optional<DeviceToken> findByFcmToken(String fcmToken);

    void deleteByUserIdAndFcmToken(String userId, String fcmToken);
}
