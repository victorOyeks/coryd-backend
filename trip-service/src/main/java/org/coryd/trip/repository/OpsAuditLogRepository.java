package org.coryd.trip.repository;

import org.coryd.trip.entity.OpsAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OpsAuditLogRepository extends JpaRepository<OpsAuditLog, String> {

    List<OpsAuditLog> findByRoutePostIdOrderByCreatedAtAsc(String routePostId);

    /** Every ops action on one SOS, issue report or rating, oldest first. */
    List<OpsAuditLog> findByTargetTypeAndTargetIdOrderByCreatedAtAsc(OpsAuditLog.TargetType targetType, String targetId);
}
