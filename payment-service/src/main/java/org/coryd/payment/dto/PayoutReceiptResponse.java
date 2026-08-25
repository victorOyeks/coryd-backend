package org.coryd.payment.dto;

import org.coryd.common.enums.PayoutStatus;

import java.math.BigDecimal;
import java.time.Instant;

/** The Withdrawal Receipt screen. receiptReference is deterministic (not random), same pattern as the trip receipt's - the same withdrawal always shows the same reference number. */
public record PayoutReceiptResponse(
        String payoutId,
        PayoutStatus status,
        BigDecimal amount,
        Instant createdAt,
        String bankName,
        String accountNumberMasked,
        String receiptReference
) {
}
