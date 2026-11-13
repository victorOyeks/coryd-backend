package org.coryd.ops.dto.money;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RevealBody(String recipientId, @NotBlank(message = "a reason is required") @Size(max = 500) String reason) {
}
