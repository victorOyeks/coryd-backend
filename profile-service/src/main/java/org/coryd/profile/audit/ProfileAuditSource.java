package org.coryd.profile.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.coryd.common.audit.OpsAuditCategory;
import org.coryd.common.audit.OpsAuditEntry;
import org.coryd.common.audit.OpsAuditJpql;
import org.coryd.common.audit.OpsAuditPage;
import org.coryd.common.audit.OpsAuditQuery;
import org.coryd.common.enums.VerificationDecisionAction;
import org.coryd.common.enums.VerificationStatus;
import org.coryd.profile.entity.VerificationDecisionLog;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * profile-service's part of the unified audit log: verification_decision_log - identity (NIN) decisions and driver
 * document decisions. Category VERIFICATION, target type VERIFICATION, target id the person decided on, subject the
 * document ("Insurance certificate") or "Identity (NIN)".
 *
 * <p>Left out: PROVIDER_RESULT (Smile ID's own late result, not an ops action) and ATTEMPTS_RESET (ops-service records
 * the same reset in the account history, which the log already shows as LICENCE_/IDENTITY_ATTEMPTS_RESET). Entries from
 * before actions were recorded show their status as the action (APPROVED / REJECTED / ...).
 */
@Service
@RequiredArgsConstructor
public class ProfileAuditSource {

    public static final String SOURCE = "PROFILE";
    static final Set<VerificationDecisionAction> EXCLUDED = EnumSet.of(VerificationDecisionAction.PROVIDER_RESULT,
            VerificationDecisionAction.ATTEMPTS_RESET);

    private final EntityManager entityManager;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public OpsAuditPage page(OpsAuditQuery q) {
        if (!q.wantsCategory(OpsAuditCategory.VERIFICATION) || !q.wantsTargetType("VERIFICATION")) {
            return new OpsAuditPage(List.of(), false);
        }
        OpsAuditJpql jpql = OpsAuditJpql.from("VerificationDecisionLog")
                .notIn("e.action", EXCLUDED)
                .eq("e.decidedById", OpsAuditQuery.blankToNull(q.actorId()))
                .eq("e.driverId", OpsAuditQuery.blankToNull(q.targetId()))
                .range("e.createdAt", q.from(), q.to())
                .before("e.createdAt", "e.id", q.beforeAt(), OpsAuditQuery.blankToNull(q.beforeId()))
                .search(q.searchPattern(), "e.note", "e.decidedBy", "e.driverId");
        if (q.actions() != null && !q.actions().isEmpty()) {
            Set<VerificationDecisionAction> actions = EnumSet.noneOf(VerificationDecisionAction.class);
            Set<VerificationStatus> legacyStatuses = EnumSet.noneOf(VerificationStatus.class);
            for (String name : q.actions()) {
                for (VerificationDecisionAction a : VerificationDecisionAction.values()) {
                    if (a.name().equals(name.trim()) && !EXCLUDED.contains(a)) {
                        actions.add(a);
                    }
                }
                for (VerificationStatus s : VerificationStatus.values()) {
                    if (s.name().equals(name.trim())) {
                        legacyStatuses.add(s);
                    }
                }
            }
            if (actions.isEmpty() && legacyStatuses.isEmpty()) {
                jpql.in("e.action", List.of());
            } else if (legacyStatuses.isEmpty()) {
                jpql.in("e.action", actions);
            } else if (actions.isEmpty()) {
                jpql.where("e.action is null and e.status in ?", legacyStatuses);
            } else {
                jpql.where("e.action in ? or (e.action is null and e.status in ?)", actions, legacyStatuses);
            }
        }
        int limit = q.limit();
        List<VerificationDecisionLog> rows = jpql.fetch(entityManager, VerificationDecisionLog.class, "e.createdAt", "e.id", limit);
        return new OpsAuditPage(rows.stream().limit(limit).map(this::entry).toList(), rows.size() > limit);
    }

    private OpsAuditEntry entry(VerificationDecisionLog d) {
        ObjectNode before = objectMapper.createObjectNode();
        before.put("status", d.getPreviousStatus() == null ? null : d.getPreviousStatus().name());
        ObjectNode after = objectMapper.createObjectNode();
        after.put("status", d.getStatus().name());
        if (d.isOverrodeProvider()) {
            after.put("overrodeProvider", true);
        }
        if (d.getProviderOutcome() != null) {
            after.put("providerOutcome", d.getProviderOutcome());
        }
        String action = d.getAction() != null ? d.getAction().name() : d.getStatus().name();
        return new OpsAuditEntry(d.getId(), d.getCreatedAt(), SOURCE, OpsAuditCategory.VERIFICATION, action, d.getDecidedById(),
                d.getDecidedBy(), "VERIFICATION", d.getDriverId(), d.getItem().label(), d.getNote(),
                d.getPreviousStatus() == null ? null : before.toString(), after.toString(), null, null);
    }
}
