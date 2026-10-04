package org.coryd.payment.dto;

import java.math.BigDecimal;
import java.time.Instant;

/** One Paystack settlement to the driver's bank. */
public record DriverPayoutItem(
        String id,
        BigDecimal amount,
        SettlementStatus status,
        Instant settlementDate
) {
}
