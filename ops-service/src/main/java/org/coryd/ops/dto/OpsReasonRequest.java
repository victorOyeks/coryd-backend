package org.coryd.ops.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A rejection or a resubmission request - reason is what the person sees. The owning service caps it further where its
 * field is shorter (255 characters for a driver document).
 */
public record OpsReasonRequest(@NotBlank(message = "is required - the person sees it") @Size(max = 500) String reason) {
}
