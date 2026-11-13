package org.coryd.ops.dto;

import java.math.BigDecimal;
import java.time.Instant;

/** A seat's payment as payment-service has it. netPaid is what the rider is out of pocket after refunds. */
public record PaymentSummary(String status, String method, String cardType, String cardLast4, String reference, Instant paidAt,
                             BigDecimal amountPaid, BigDecimal refunded, BigDecimal refundPending, BigDecimal netPaid) {
}
