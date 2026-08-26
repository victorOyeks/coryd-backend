package org.coryd.profile.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/** One call to decide any/all of a driver's reviewable items - approve some, reject others, or just one, in a single request instead of a separate endpoint (and a separate id - vehicleId, documentId) per item type. */
public record VerificationDecisionBatchRequest(
        @NotEmpty @Valid List<ItemDecision> decisions,
        String reviewedBy
) {
}
