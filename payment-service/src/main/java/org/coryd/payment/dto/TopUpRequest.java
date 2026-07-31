package org.coryd.payment.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record TopUpRequest(@NotNull @Positive BigDecimal amount, String email, String callbackUrl) {
}
