package org.coryd.payment.dto;

import java.math.BigDecimal;

public record PeriodEarningsSummary(BigDecimal grossEarned, BigDecimal commission, BigDecimal netEarned) {
}
