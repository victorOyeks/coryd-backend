package org.coryd.payment.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record RecordSettlementDebtRequest(
        @NotNull @Positive BigDecimal amount,
        String reason
) {
}
