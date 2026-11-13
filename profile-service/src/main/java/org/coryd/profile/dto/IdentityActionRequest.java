package org.coryd.profile.dto;

import jakarta.validation.constraints.Size;

/**
 * An ops action on a person's identity (NIN) check. Approve and reset-attempts take an optional note (required to approve
 * over Smile ID's failed check, or over a rejection); reject needs reason, which the person sees. reviewedBy is the admin's
 * name, set by ops-service.
 */
public record IdentityActionRequest(@Size(max = 500) String note, @Size(max = 500) String reason, @Size(max = 150) String reviewedBy) {
}
