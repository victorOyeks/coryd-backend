package org.coryd.payment.dto;

import java.math.BigDecimal;

public record WalletBalanceResponse(BigDecimal availableBalance, String currency) {
}
