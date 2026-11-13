package org.coryd.payment.ops.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** amount null refunds everything still refundable. idempotencyKey: the console's own id for this submit - a resend returns the first result. */
public record ManualRefundRequest(@Positive BigDecimal amount,
                                  @NotBlank(message = "a reason is required") @Size(max = 500) String reason,
                                  @Size(max = 100) String idempotencyKey) {
}
