package org.coryd.ops.dto;

import java.math.BigDecimal;

/** expected: what the rider should get back. refunded / pending: what payment-service says has gone, or is going, back. */
public record RefundOutcome(RefundState state, BigDecimal expected, BigDecimal refunded, BigDecimal pending) {
}
