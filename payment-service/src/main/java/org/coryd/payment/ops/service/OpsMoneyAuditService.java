package org.coryd.payment.ops.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.payment.ops.entity.OpsMoneyAudit;
import org.coryd.payment.ops.repository.OpsMoneyAuditRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/** Writes the money audit trail, in the caller's transaction - an action that rolls back leaves no audit row. */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpsMoneyAuditService {

    private final OpsMoneyAuditRepository auditRepository;
    private final ObjectMapper objectMapper;

    public OpsMoneyAudit record(OpsActor actor, OpsMoneyAudit.Action action, OpsMoneyAudit.TargetType targetType, String targetId,
                                String requestId, BigDecimal amount, Object before, Object after, String reason) {
        OpsMoneyAudit audit = auditRepository.save(OpsMoneyAudit.builder()
                .actorId(actor.id())
                .actorName(actor.name())
                .action(action)
                .targetType(targetType)
                .targetId(targetId)
                .requestId(requestId)
                .amount(amount)
                .beforeState(json(before))
                .afterState(json(after))
                .reason(truncate(reason, 500))
                .build());
        log.info("OPS MONEY {} {} {} {} amount={} by {} ({}): {}", action, targetType, targetId, requestId == null ? "" : requestId,
                amount, actor.id(), actor.name(), reason);
        return audit;
    }

    private String json(Object state) {
        if (state == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(state);
        } catch (Exception e) {
            return String.valueOf(state);
        }
    }

    static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
