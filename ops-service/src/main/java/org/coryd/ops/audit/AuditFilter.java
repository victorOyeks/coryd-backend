package org.coryd.ops.audit;

import org.coryd.common.audit.OpsAuditCategory;

import java.time.LocalDate;
import java.util.List;

/**
 * The console's audit filter. from/to are Lagos calendar days, both inclusive. actions/categories/targetTypes match any
 * of their values. search: reason or note, actor name, target id - a console reference ("T-2F2F53") searches by its id.
 */
public record AuditFilter(String actorId, List<String> actions, List<OpsAuditCategory> categories, List<String> targetTypes,
                          String targetId, LocalDate from, LocalDate to, String search) {
}
