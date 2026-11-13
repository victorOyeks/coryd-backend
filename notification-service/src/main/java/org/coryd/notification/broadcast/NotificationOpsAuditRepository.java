package org.coryd.notification.broadcast;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NotificationOpsAuditRepository extends JpaRepository<NotificationOpsAudit, String> {

    List<NotificationOpsAudit> findByTargetTypeAndTargetIdOrderByCreatedAtAsc(String targetType, String targetId);
}
