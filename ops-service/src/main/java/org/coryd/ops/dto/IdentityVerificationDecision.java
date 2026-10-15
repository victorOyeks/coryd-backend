package org.coryd.ops.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** reason is required to reject - the rider sees it. */
public record IdentityVerificationDecision(@NotNull Boolean approve, @Size(max = 500) String reason) {
}
