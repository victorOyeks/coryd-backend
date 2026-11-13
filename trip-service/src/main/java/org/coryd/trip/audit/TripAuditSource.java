package org.coryd.trip.audit;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.coryd.common.audit.OpsAuditCategory;
import org.coryd.common.audit.OpsAuditEntry;
import org.coryd.common.audit.OpsAuditJpql;
import org.coryd.common.audit.OpsAuditPage;
import org.coryd.common.audit.OpsAuditQuery;
import org.coryd.trip.entity.OpsAuditLog;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * trip-service's part of the unified audit log: ops_audit_log. Trips and bookings (ROUTE, BOOKING) are category TRIPS;
 * SOS, ISSUE and RATING are SAFETY. relatedId is the trip the action was on; subject the fault, when one was given.
 */
@Service
@RequiredArgsConstructor
public class TripAuditSource {

    public static final String SOURCE = "TRIP";
    static final Set<OpsAuditLog.TargetType> SAFETY = EnumSet.of(OpsAuditLog.TargetType.SOS, OpsAuditLog.TargetType.ISSUE,
            OpsAuditLog.TargetType.RATING);

    private final EntityManager entityManager;

    @Transactional(readOnly = true)
    public OpsAuditPage page(OpsAuditQuery q) {
        Set<OpsAuditLog.TargetType> types = EnumSet.noneOf(OpsAuditLog.TargetType.class);
        for (OpsAuditLog.TargetType t : OpsAuditLog.TargetType.values()) {
            OpsAuditCategory category = SAFETY.contains(t) ? OpsAuditCategory.SAFETY : OpsAuditCategory.TRIPS;
            if (q.wantsCategory(category) && q.wantsTargetType(t.name())) {
                types.add(t);
            }
        }
        Set<OpsAuditLog.Action> actions = null;
        if (q.actions() != null && !q.actions().isEmpty()) {
            actions = EnumSet.noneOf(OpsAuditLog.Action.class);
            for (OpsAuditLog.Action a : OpsAuditLog.Action.values()) {
                if (q.actions().stream().anyMatch(n -> n.trim().equals(a.name()))) {
                    actions.add(a);
                }
            }
        }
        OpsAuditJpql jpql = OpsAuditJpql.from("OpsAuditLog")
                .in("e.targetType", types.size() == OpsAuditLog.TargetType.values().length ? null : types)
                .in("e.action", actions)
                .eq("e.actorId", OpsAuditQuery.blankToNull(q.actorId()))
                .eq("e.targetId", OpsAuditQuery.blankToNull(q.targetId()))
                .range("e.createdAt", q.from(), q.to())
                .before("e.createdAt", "e.id", q.beforeAt(), OpsAuditQuery.blankToNull(q.beforeId()))
                .search(q.searchPattern(), "e.reason", "e.actorName", "e.targetId", "e.routePostId");
        int limit = q.limit();
        List<OpsAuditLog> rows = jpql.fetch(entityManager, OpsAuditLog.class, "e.createdAt", "e.id", limit);
        return new OpsAuditPage(rows.stream().limit(limit).map(TripAuditSource::entry).toList(), rows.size() > limit);
    }

    private static OpsAuditEntry entry(OpsAuditLog a) {
        OpsAuditCategory category = SAFETY.contains(a.getTargetType()) ? OpsAuditCategory.SAFETY : OpsAuditCategory.TRIPS;
        return new OpsAuditEntry(a.getId(), a.getCreatedAt(), SOURCE, category, a.getAction().name(), a.getActorId(),
                a.getActorName(), a.getTargetType().name(), a.getTargetId(), a.getFault() == null ? null : "Fault: " + a.getFault().name(),
                a.getReason(), a.getBeforeState(), a.getAfterState(), null, a.getRoutePostId());
    }
}
