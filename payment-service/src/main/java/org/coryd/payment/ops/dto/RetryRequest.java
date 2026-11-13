package org.coryd.payment.ops.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RetryRequest(@NotBlank(message = "a reason is required") @Size(max = 500) String reason,
                           @Size(max = 100) String idempotencyKey) {
}
