package org.coryd.profile.dto;

import jakarta.validation.constraints.Size;

/**
 * Approve one driver document. note goes into the audit log; it's required when the approval overrides Smile ID's failed
 * check or an earlier rejection. reviewedBy is set by ops-service.
 */
public record ItemApprovalRequest(@Size(max = 500) String note, @Size(max = 150) String reviewedBy) {
}
