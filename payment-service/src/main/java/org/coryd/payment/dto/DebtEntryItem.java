package org.coryd.payment.dto;

import java.math.BigDecimal;
import java.time.Instant;

/** amount is signed - positive added to what's owed, negative paid it down. */
public record DebtEntryItem(
        String id,
        DebtEntryType type,
        BigDecimal amount,
        BigDecimal balanceAfter,
        String bookingId,
        String reference,
        String note,
        Instant occurredAt
) {
}
