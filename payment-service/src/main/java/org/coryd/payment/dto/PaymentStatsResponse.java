package org.coryd.payment.dto;

import java.math.BigDecimal;

/** payoutCount/payoutAmount are Paystack's settlements to driver banks in the window; driverDebt is point-in-time. */
public record PaymentStatsResponse(
        BigDecimal grossFares,
        BigDecimal commission,
        BigDecimal creditedToDrivers,
        long refundCount,
        BigDecimal refundAmount,
        long payoutCount,
        BigDecimal payoutAmount,
        BigDecimal driverDebt
) {
}
