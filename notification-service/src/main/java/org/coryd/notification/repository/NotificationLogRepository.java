package org.coryd.notification.repository;

import org.coryd.notification.entity.NotificationLog;
import org.springframework.data.jpa.repository.JpaRepository;


public interface NotificationLogRepository extends JpaRepository<NotificationLog, String> {
}
