package org.coryd.ops.dto.safety;

/** Whoever raised an SOS or filed a report: role RIDER or DRIVER, ref "R-…" / "D-…". */
public record PersonSummary(String id, String ref, String role, String name, String phone, String photoUrl) {
}
