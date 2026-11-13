package org.coryd.trip.dto.ops;

import java.time.Instant;

/** One ops action from the audit log - before/after are the JSON the action recorded. */
public record OpsAuditEntry(String id, Instant at, String actorId, String actorName, String action, String note,
                            String before, String after) {
}
