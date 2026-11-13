package org.coryd.ops.dto.money;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** DEBIT adds to what the driver owes; CREDIT takes it down. */
public record DebtAdjustmentBody(@NotNull(message = "direction is required (DEBIT or CREDIT)") Direction direction,
                                 @NotNull(message = "amount is required") @Positive BigDecimal amount,
                                 @NotBlank(message = "a reason is required") @Size(max = 500) String reason,
                                 @Size(max = 100) String idempotencyKey) {

    public enum Direction { DEBIT, CREDIT }
}
