package org.coryd.ops.audit;

import com.fasterxml.jackson.databind.JsonNode;
import org.coryd.common.audit.OpsAuditCategory;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One row of the unified audit log. id is "{source}:{id in its own table}". target.ref is the console's short reference
 * where it has one (T-, B-, S-, D-); target.name the person's name for account, admin and verification targets.
 * before/after are the state as JSON (null when not recorded). relatedId: the trip a booking or SOS action was on, or the
 * approval request a money action belongs to.
 */
public record AuditItem(String id, Instant at, String source, OpsAuditCategory category, String action, Actor actor, Target target,
                        String subject, String reason, JsonNode before, JsonNode after, BigDecimal amount, String relatedId,
                        String relatedRef) {

    public record Actor(String id, String name) {
    }

    public record Target(String type, String id, String ref, String name) {
    }
}
