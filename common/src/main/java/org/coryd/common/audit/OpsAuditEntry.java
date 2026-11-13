package org.coryd.common.audit;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One ops action as an owning service reports it to ops-service's unified audit log (GET /internal/ops/audit).
 *
 * @param id         the row's id in its own table - unique within its source, and with {@code at} the keyset cursor
 * @param source     IDENTITY, PROFILE, TRIP or PAYMENT
 * @param action     the source's own action name (SUSPENDED, OVERRIDE_APPROVED, CANCEL_TRIP, REFUND_EXECUTED...)
 * @param targetType ACCOUNT, ADMIN, VERIFICATION, ROUTE, BOOKING, SOS, ISSUE, RATING, FARE_HOLD, SETTLEMENT, DRIVER,
 *                   MONEY_REQUEST or PAYOUT_ACCOUNT
 * @param subject    what about the target, when the target alone doesn't say (the document: "Insurance certificate")
 * @param before     JSON text of the state before, or null
 * @param after      JSON text of the state after, or null
 * @param relatedId  the trip a booking/SOS action was on, or the approval request a money action belongs to
 */
public record OpsAuditEntry(String id, Instant at, String source, OpsAuditCategory category, String action, String actorId,
                            String actorName, String targetType, String targetId, String subject, String reason, String before,
                            String after, BigDecimal amount, String relatedId) {
}
