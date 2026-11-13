package org.coryd.trip.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.RequiredArgsConstructor;
import org.coryd.trip.dto.ops.OpsAuditEntry;
import org.coryd.trip.entity.OpsAuditLog;
import org.coryd.trip.repository.OpsAuditLogRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

/**
 * The ops audit log for SOS, issue-report and rating actions - the same table, and the same who / what / before / after /
 * why, as the trip and booking actions. Written in the caller's transaction, so an action and its record stand or fall
 * together. An action on a trip also shows on that trip's timeline as OPS_ACTION.
 */
@Component
@RequiredArgsConstructor
public class OpsAuditWriter {

    static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final OpsAuditLogRepository repository;

    public OpsAuditLog write(String actorId, String actorName, OpsAuditLog.Action action, OpsAuditLog.TargetType targetType,
                             String targetId, String routePostId, Object before, Object after, String note) {
        return repository.save(OpsAuditLog.builder()
                .actorId(actorId)
                .actorName(actorName == null || actorName.isBlank() ? actorId : actorName.trim())
                .action(action)
                .targetType(targetType)
                .targetId(targetId)
                .routePostId(routePostId)
                .beforeState(json(before))
                .afterState(json(after))
                .reason(note == null || note.isBlank() ? "-" : truncate(note.trim(), 500))
                .build());
    }

    public List<OpsAuditEntry> history(OpsAuditLog.TargetType targetType, String targetId) {
        return repository.findByTargetTypeAndTargetIdOrderByCreatedAtAsc(targetType, targetId).stream()
                .map(a -> new OpsAuditEntry(a.getId(), a.getCreatedAt(), a.getActorId(), a.getActorName(), a.getAction().name(),
                        "-".equals(a.getReason()) ? null : a.getReason(), a.getBeforeState(), a.getAfterState()))
                .toList();
    }

    /** Runs once the surrounding transaction commits (or straight away outside one) - events must never announce a rolled-back change. */
    public static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    static String json(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
