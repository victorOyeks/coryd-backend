package org.coryd.ops.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/** reviewedBy isn't accepted here - it's always the signed-in admin. */
public record VerificationDecisionRequest(
        @NotEmpty @Valid List<ItemDecision> decisions
) {
}
