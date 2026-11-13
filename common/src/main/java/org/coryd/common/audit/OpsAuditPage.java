package org.coryd.common.audit;

import java.util.List;

/** Up to `size` entries newest first after the cursor, and whether there are more. */
public record OpsAuditPage(List<OpsAuditEntry> items, boolean hasMore) {
}
