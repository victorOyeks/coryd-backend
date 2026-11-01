package org.coryd.profile.dto;

import jakarta.validation.constraints.Size;

/** Approve Driver / Reject. note: optional, kept with each approval in the decision history. reviewedBy is set by ops-service. */
public record ReviewDecisionRequest(
        @Size(max = 500) String note,
        @Size(max = 150) String reviewedBy
) {
}
