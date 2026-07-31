package org.coryd.notification.repository;

import org.coryd.notification.entity.UserNotification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface UserNotificationRepository extends JpaRepository<UserNotification, String> {
    List<UserNotification> findByUserIdOrderByCreatedAtDesc(String userId);
    long countByUserIdAndReadFalse(String userId);

    @Modifying
    @Query("""
            update UserNotification n
            set n.read = true, n.readAt = :now
            where n.userId = :userId and n.read = false
            """)
    int markAllReadForUser(@Param("userId") String userId, @Param("now") Instant now);
}
