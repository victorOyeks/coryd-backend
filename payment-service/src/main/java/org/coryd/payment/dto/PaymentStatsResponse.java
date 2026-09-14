package org.coryd.payment.dto;

import java.math.BigDecimal;

public record PaymentStatsResponse(
        BigDecimal grossFares,
        BigDecimal grossViaCard,
        BigDecimal grossViaWallet,
        BigDecimal commission,
        BigDecimal creditedToDrivers,
        long refundCount,
        BigDecimal refundAmount,
        long payoutCount,
        BigDecimal payoutAmount,
        BigDecimal unsettledDriverBalance
) {
}
