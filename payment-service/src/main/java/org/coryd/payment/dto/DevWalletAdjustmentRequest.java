package org.coryd.payment.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record DevWalletAdjustmentRequest(@NotNull @Positive BigDecimal amount, String reason) {
}
