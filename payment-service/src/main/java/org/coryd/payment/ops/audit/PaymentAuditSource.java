package org.coryd.payment.ops.audit;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.coryd.common.audit.OpsAuditCategory;
import org.coryd.common.audit.OpsAuditEntry;
import org.coryd.common.audit.OpsAuditJpql;
import org.coryd.common.audit.OpsAuditPage;
import org.coryd.common.audit.OpsAuditQuery;
import org.coryd.payment.ops.entity.OpsMoneyAudit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * payment-service's part of the unified audit log: ops_money_audit, category MONEY. Target types as recorded, except
 * REQUEST, which is MONEY_REQUEST in the unified log (an approval request). relatedId is the approval request.
 */
@Service
@RequiredArgsConstructor
public class PaymentAuditSource {

    public static final String SOURCE = "PAYMENT";

    private final EntityManager entityManager;

    static String unified(OpsMoneyAudit.TargetType type) {
        return type == OpsMoneyAudit.TargetType.REQUEST ? "MONEY_REQUEST" : type.name();
    }

    @Transactional(readOnly = true)
    public OpsAuditPage page(OpsAuditQuery q) {
        if (!q.wantsCategory(OpsAuditCategory.MONEY)) {
            return new OpsAuditPage(List.of(), false);
        }
        Set<OpsMoneyAudit.TargetType> types = EnumSet.noneOf(OpsMoneyAudit.TargetType.class);
        for (OpsMoneyAudit.TargetType t : OpsMoneyAudit.TargetType.values()) {
            if (q.wantsTargetType(unified(t))) {
                types.add(t);
            }
        }
        Set<OpsMoneyAudit.Action> actions = null;
        if (q.actions() != null && !q.actions().isEmpty()) {
            actions = EnumSet.noneOf(OpsMoneyAudit.Action.class);
            for (OpsMoneyAudit.Action a : OpsMoneyAudit.Action.values()) {
                if (q.actions().stream().anyMatch(n -> n.trim().equals(a.name()))) {
                    actions.add(a);
                }
            }
        }
        OpsAuditJpql jpql = OpsAuditJpql.from("OpsMoneyAudit")
                .in("e.targetType", types.size() == OpsMoneyAudit.TargetType.values().length ? null : types)
                .in("e.action", actions)
                .eq("e.actorId", OpsAuditQuery.blankToNull(q.actorId()))
                .range("e.createdAt", q.from(), q.to())
                .before("e.createdAt", "e.id", q.beforeAt(), OpsAuditQuery.blankToNull(q.beforeId()))
                .search(q.searchPattern(), "e.reason", "e.actorName", "e.targetId", "e.requestId");
        String targetId = OpsAuditQuery.blankToNull(q.targetId());
        if (targetId != null) {
            jpql.where("e.targetId = ? or e.requestId = ?", targetId, targetId);
        }
        int limit = q.limit();
        List<OpsMoneyAudit> rows = jpql.fetch(entityManager, OpsMoneyAudit.class, "e.createdAt", "e.id", limit);
        return new OpsAuditPage(rows.stream().limit(limit).map(PaymentAuditSource::entry).toList(), rows.size() > limit);
    }

    private static OpsAuditEntry entry(OpsMoneyAudit a) {
        return new OpsAuditEntry(a.getId(), a.getCreatedAt(), SOURCE, OpsAuditCategory.MONEY, a.getAction().name(), a.getActorId(),
                a.getActorName(), unified(a.getTargetType()), a.getTargetId(), null, a.getReason(), a.getBeforeState(),
                a.getAfterState(), a.getAmount(), a.getRequestId());
    }
}
