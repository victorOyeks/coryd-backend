package org.coryd.ops.audit;

import java.util.List;

/** A page of the unified audit log, newest first. Pass nextCursor back as ?cursor= while hasMore. */
public record AuditFeed(List<AuditItem> items, boolean hasMore, String nextCursor, int size) {
}
