package org.coryd.identity.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.coryd.common.audit.OpsAuditCategory;
import org.coryd.common.audit.OpsAuditEntry;
import org.coryd.common.audit.OpsAuditJpql;
import org.coryd.common.audit.OpsAuditPage;
import org.coryd.common.audit.OpsAuditQuery;
import org.coryd.common.enums.AccountActionType;
import org.coryd.identity.entity.AccountAction;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * identity-service's part of the unified audit log: account_actions. Admin-team actions (OPS_*) are category TEAM with
 * target type ADMIN; the rest are category ACCOUNT with target type ACCOUNT. The target is always the account the action
 * was on. changes [{field, before, after}] become before/after objects keyed by field.
 */
@Service
@RequiredArgsConstructor
public class IdentityAuditSource {

    public static final String SOURCE = "IDENTITY";

    private final EntityManager entityManager;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public OpsAuditPage page(OpsAuditQuery q) {
        Set<AccountActionType> allowed = EnumSet.allOf(AccountActionType.class);
        if (!q.wantsCategory(OpsAuditCategory.TEAM) || !q.wantsTargetType("ADMIN")) {
            allowed.removeIf(AccountActionType::isTeamAction);
        }
        if (!q.wantsCategory(OpsAuditCategory.ACCOUNT) || !q.wantsTargetType("ACCOUNT")) {
            allowed.removeIf(a -> !a.isTeamAction());
        }
        if (q.actions() != null && !q.actions().isEmpty()) {
            Set<String> wanted = q.actions().stream().map(String::trim).collect(Collectors.toSet());
            allowed.removeIf(a -> !wanted.contains(a.name()));
        }
        OpsAuditJpql jpql = OpsAuditJpql.from("AccountAction")
                .in("e.action", allowed.size() == AccountActionType.values().length ? null : allowed)
                .eq("e.actorId", OpsAuditQuery.blankToNull(q.actorId()))
                .eq("e.userId", OpsAuditQuery.blankToNull(q.targetId()))
                .range("e.createdAt", q.from(), q.to())
                .before("e.createdAt", "e.id", q.beforeAt(), OpsAuditQuery.blankToNull(q.beforeId()))
                .search(q.searchPattern(), "e.reason", "e.actorName", "e.userId");
        int limit = q.limit();
        List<AccountAction> rows = jpql.fetch(entityManager, AccountAction.class, "e.createdAt", "e.id", limit);
        return new OpsAuditPage(rows.stream().limit(limit).map(this::entry).toList(), rows.size() > limit);
    }

    private OpsAuditEntry entry(AccountAction a) {
        boolean team = a.getAction().isTeamAction();
        ObjectNode before = null;
        ObjectNode after = null;
        if (a.getChanges() != null && !a.getChanges().isBlank()) {
            try {
                before = objectMapper.createObjectNode();
                after = objectMapper.createObjectNode();
                for (var change : objectMapper.readTree(a.getChanges())) {
                    String field = change.path("field").asText();
                    before.set(field, change.path("before"));
                    after.set(field, change.path("after"));
                }
            } catch (Exception e) {
                before = null;
                after = null;
            }
        }
        return new OpsAuditEntry(a.getId(), a.getCreatedAt(), SOURCE, team ? OpsAuditCategory.TEAM : OpsAuditCategory.ACCOUNT,
                a.getAction().name(), a.getActorId(), a.getActorName(), team ? "ADMIN" : "ACCOUNT", a.getUserId(), null,
                a.getReason(), before == null ? null : before.toString(), after == null ? null : after.toString(), null, null);
    }

    /** For docs/tests: every action this source can report. */
    public static List<String> actions() {
        return Arrays.stream(AccountActionType.values()).map(Enum::name).toList();
    }
}
