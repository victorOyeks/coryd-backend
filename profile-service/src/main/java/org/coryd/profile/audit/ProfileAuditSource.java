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
import org.coryd.profile.privacy.PiiRevealLog;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * profile-service's part of the unified audit log: verification_decision_log - identity (NIN) decisions and driver
 * document decisions. Category VERIFICATION, target type VERIFICATION, target id the person decided on, subject the
 * document ("Insurance certificate") or "Identity (NIN)". Also pii_reveal_log - an admin revealing someone's full NIN,
 * licence number or date of birth: category PRIVACY, action PII_REVEALED, target ACCOUNT (the person), subject the field.
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

    /** A reveal of a person's full NIN, licence number or date of birth (pii_reveal_log), category PRIVACY. */
    public static final String PII_REVEALED = "PII_REVEALED";

    /**
     * Both tables, newest first: each is asked for up to size + 1 rows after the cursor (the same keyset - created_at desc,
     * id desc - on both), the two are merged in that order and the first size kept.
     */
    @Transactional(readOnly = true)
    public OpsAuditPage page(OpsAuditQuery q) {
        int limit = q.limit();
        List<OpsAuditEntry> all = new java.util.ArrayList<>();
        boolean more = false;
        if (q.wantsCategory(OpsAuditCategory.VERIFICATION) && q.wantsTargetType("VERIFICATION")) {
            OpsAuditPage decisions = decisions(q);
            all.addAll(decisions.items());
            more = decisions.hasMore();
        }
        if (q.wantsCategory(OpsAuditCategory.PRIVACY) && q.wantsTargetType("ACCOUNT")) {
            OpsAuditPage reveals = reveals(q);
            all.addAll(reveals.items());
            more |= reveals.hasMore();
        }
        all.sort(java.util.Comparator.comparing(OpsAuditEntry::at).thenComparing(OpsAuditEntry::id).reversed());
        return new OpsAuditPage(all.stream().limit(limit).toList(), more || all.size() > limit);
    }

    private OpsAuditPage reveals(OpsAuditQuery q) {
        if (q.actions() != null && !q.actions().isEmpty() && q.actions().stream().noneMatch(a -> PII_REVEALED.equals(a.trim()))) {
            return new OpsAuditPage(List.of(), false);
        }
        OpsAuditJpql jpql = OpsAuditJpql.from("PiiRevealLog")
                .eq("e.actorId", OpsAuditQuery.blankToNull(q.actorId()))
                .eq("e.userId", OpsAuditQuery.blankToNull(q.targetId()))
                .range("e.createdAt", q.from(), q.to())
                .before("e.createdAt", "e.id", q.beforeAt(), OpsAuditQuery.blankToNull(q.beforeId()))
                .search(q.searchPattern(), "e.reason", "e.actorName", "e.userId");
        int limit = q.limit();
        List<PiiRevealLog> rows = jpql.fetch(entityManager, PiiRevealLog.class, "e.createdAt", "e.id", limit);
        return new OpsAuditPage(rows.stream().limit(limit).map(this::entry).toList(), rows.size() > limit);
    }

    private OpsAuditEntry entry(PiiRevealLog r) {
        ObjectNode after = objectMapper.createObjectNode();
        after.put("field", r.getField().name());
        after.put("shownAs", r.getMaskedValue());
        return new OpsAuditEntry(r.getId(), r.getCreatedAt(), SOURCE, OpsAuditCategory.PRIVACY, PII_REVEALED, r.getActorId(),
                r.getActorName(), "ACCOUNT", r.getUserId(), r.getField().label(), r.getReason(), null, after.toString(), null, null);
    }

    private OpsAuditPage decisions(OpsAuditQuery q) {
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
