package org.coryd.notification.repository;

import org.coryd.notification.entity.DeviceToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface DeviceTokenRepository extends JpaRepository<DeviceToken, String> {
    List<DeviceToken> findByUserId(String userId);

    List<DeviceToken> findByUserIdIn(java.util.Collection<String> userIds);

    Optional<DeviceToken> findByFcmToken(String fcmToken);

    void deleteByUserIdAndFcmToken(String userId, String fcmToken);

    /** The user's devices last registered at or before the cut-off - a phone that signed in again since keeps its token. */
    @Modifying
    @Query("delete from DeviceToken d where d.userId = :userId and d.updatedAt <= :before")
    int deleteRegisteredBefore(@Param("userId") String userId, @Param("before") Instant before);
}
