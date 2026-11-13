package org.coryd.payment.ops.dto;

import java.math.BigDecimal;
import java.time.Instant;

/** One Paystack settlement to a driver's bank. status PENDING / PROCESSING / PAID / FAILED (Paystack's success is PAID). */
public record PayoutItem(String id, String driverId, BigDecimal amount, String status, Instant settlementDate, String reference,
                         Instant lastSyncedAt, boolean retryable, String openRetryRequestId) {
}
