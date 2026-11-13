package org.coryd.common.audit;

import org.springframework.format.annotation.DateTimeFormat;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * The filter every owning service's GET /internal/ops/audit takes (bound from query parameters), so ops-service can
 * send one shape everywhere. Everything is optional; lists match any of their values. from is inclusive, to exclusive.
 * beforeAt + beforeId is the keyset cursor: only entries older than that one (same time: smaller id, in the database's
 * order) come back. search is matched case-insensitively against reason, actor name, target id and the action.
 */
public record OpsAuditQuery(String actorId,
                            List<String> actions,
                            List<OpsAuditCategory> categories,
                            List<String> targetTypes,
                            String targetId,
                            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                            String search,
                            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant beforeAt,
                            String beforeId,
                            Integer size) {

    public static final int DEFAULT_SIZE = 50;
    /** A source sends at most this many per call - the CSV export reads in pages this big. */
    public static final int MAX_SIZE = 500;

    public int limit() {
        return size == null ? DEFAULT_SIZE : Math.clamp(size, 1, MAX_SIZE);
    }

    public boolean wantsCategory(OpsAuditCategory category) {
        return categories == null || categories.isEmpty() || categories.contains(category);
    }

    public boolean wantsTargetType(String targetType) {
        return targetTypes == null || targetTypes.isEmpty() || targetTypes.stream().anyMatch(targetType::equalsIgnoreCase);
    }

    /** The search term as a lower-case LIKE pattern ("%term%"), or null. */
    public String searchPattern() {
        if (search == null || search.isBlank()) {
            return null;
        }
        String escaped = search.trim().toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }

    public static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
