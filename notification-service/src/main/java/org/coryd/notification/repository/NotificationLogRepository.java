package org.coryd.notification.repository;

import org.coryd.notification.entity.NotificationLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;


public interface NotificationLogRepository extends JpaRepository<NotificationLog, String> {

    /** Deliveries of these kinds in a window, oldest first - the Ops Console's "who was told, on which channel". */
    List<NotificationLog> findByEventTypeInAndCreatedAtBetweenOrderByCreatedAtAsc(Collection<String> eventTypes, Instant from, Instant to);
}
