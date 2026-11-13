package org.coryd.payment.ops.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.coryd.payment.ops.entity.OpsMoneyRequest;

import java.math.BigDecimal;

/** DEBIT adds to what the driver owes, CREDIT takes it down (never below zero). */
public record DebtAdjustmentRequest(@NotNull(message = "direction is required (DEBIT or CREDIT)") OpsMoneyRequest.Direction direction,
                                    @NotNull(message = "amount is required") @Positive BigDecimal amount,
                                    @NotBlank(message = "a reason is required") @Size(max = 500) String reason,
                                    @Size(max = 100) String idempotencyKey) {
}
