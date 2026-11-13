package org.coryd.ops.dto.money;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** amount null refunds everything still refundable. */
public record ManualRefundBody(@Positive BigDecimal amount,
                               @NotBlank(message = "a reason is required") @Size(max = 500) String reason,
                               @Size(max = 100) String idempotencyKey) {
}
