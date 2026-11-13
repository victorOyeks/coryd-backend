package org.coryd.ops.dto;

import jakarta.validation.constraints.Size;

/**
 * An optional note for the audit log: approving an identity check or a document, or resetting identity tries. Required by
 * the owning service when the approval overrides Smile ID's failed check or a rejection.
 */
public record OpsNoteRequest(@Size(max = 500) String note) {
}
