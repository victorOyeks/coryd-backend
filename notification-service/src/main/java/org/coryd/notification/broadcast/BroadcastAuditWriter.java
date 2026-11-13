package org.coryd.notification.broadcast;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Writes notification-service's ops audit rows (notification.ops_audit_log). */
@Component
@RequiredArgsConstructor
public class BroadcastAuditWriter {

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final NotificationOpsAuditRepository repository;

    public NotificationOpsAudit write(String actorId, String actorName, String action, String targetType, String targetId,
                                      Object before, Object after, String reason) {
        return repository.save(NotificationOpsAudit.builder().actorId(actorId).actorName(actorName).action(action).targetType(targetType)
                .targetId(targetId).beforeState(json(before)).afterState(json(after))
                .reason(reason == null || reason.isBlank() ? "-" : reason.length() > 500 ? reason.substring(0, 500) : reason).build());
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
}
