package org.coryd.notification.audit;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.coryd.common.audit.OpsAuditCategory;
import org.coryd.common.audit.OpsAuditEntry;
import org.coryd.common.audit.OpsAuditJpql;
import org.coryd.common.audit.OpsAuditPage;
import org.coryd.common.audit.OpsAuditQuery;
import org.coryd.notification.broadcast.NotificationOpsAudit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * notification-service's part of the unified audit log (docs/ops-console/audit-log.md): ops_audit_log - broadcasts
 * (BROADCAST_CREATED / _SCHEDULED / _CANCELLED), emailed reports sent by hand (REPORT_SENT) and the daily jobs
 * (ONBOARDING_REMINDERS_SENT, VERIFICATION_DIGEST_SENT). Everything here is category NOTIFICATIONS. The scheduler's runs
 * are recorded with actor id "system", which the feed shows as System.
 */
@Service
@RequiredArgsConstructor
public class NotificationAuditSource {

    public static final String SOURCE = "NOTIFICATION";
    public static final Set<String> TARGET_TYPES = Set.of("BROADCAST", "REPORT", "NOTIFICATION_JOB");

    private final EntityManager entityManager;

    @Transactional(readOnly = true)
    public OpsAuditPage page(OpsAuditQuery q) {
        if (!q.wantsCategory(OpsAuditCategory.NOTIFICATIONS)) {
            return new OpsAuditPage(List.of(), false);
        }
        List<String> types = TARGET_TYPES.stream().filter(q::wantsTargetType).sorted().toList();
        if (types.isEmpty()) {
            return new OpsAuditPage(List.of(), false);
        }
        List<String> actions = q.actions() == null || q.actions().isEmpty() ? null
                : q.actions().stream().filter(a -> a != null && !a.isBlank()).map(String::trim).toList();
        OpsAuditJpql jpql = OpsAuditJpql.from("NotificationOpsAudit")
                .in("e.targetType", types.size() == TARGET_TYPES.size() ? null : types)
                .in("e.action", actions)
                .eq("e.actorId", OpsAuditQuery.blankToNull(q.actorId()))
                .eq("e.targetId", OpsAuditQuery.blankToNull(q.targetId()))
                .range("e.createdAt", q.from(), q.to())
                .before("e.createdAt", "e.id", q.beforeAt(), OpsAuditQuery.blankToNull(q.beforeId()))
                .search(q.searchPattern(), "e.reason", "e.actorName", "e.targetId");
        int limit = q.limit();
        List<NotificationOpsAudit> rows = jpql.fetch(entityManager, NotificationOpsAudit.class, "e.createdAt", "e.id", limit);
        return new OpsAuditPage(rows.stream().limit(limit).map(NotificationAuditSource::entry).toList(), rows.size() > limit);
    }

    static OpsAuditEntry entry(NotificationOpsAudit a) {
        return new OpsAuditEntry(a.getId(), a.getCreatedAt(), SOURCE, OpsAuditCategory.NOTIFICATIONS, a.getAction(), a.getActorId(),
                a.getActorName(), a.getTargetType(), a.getTargetId(), null, a.getReason(), a.getBeforeState(), a.getAfterState(), null, null);
    }
}
