package org.coryd.payment.dto;

import org.coryd.common.enums.PayoutStatus;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One row in the Wallet screen's mixed Trip/Withdrawal feed - type discriminates which fields are
 * populated. TRIP rows carry routePostId only (no origin/destination text) - the client resolves
 * that itself via trip-service's GET /routes/batch, same "client composes single-service calls"
 * convention used everywhere else in this codebase; payment-service has no route data to enrich
 * with directly.
 */
public record WalletTransactionItem(
        WalletTransactionType type,
        String id,
        Instant occurredAt,

        // TRIP fields
        String routePostId,
        BigDecimal grossFare,
        BigDecimal commissionAmount,
        BigDecimal netCredit,

        // WITHDRAWAL fields
        BigDecimal amount,
        PayoutStatus payoutStatus
) {
}
