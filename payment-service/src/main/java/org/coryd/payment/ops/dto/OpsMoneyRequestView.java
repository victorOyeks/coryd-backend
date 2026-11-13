package org.coryd.payment.ops.dto;

import org.coryd.payment.ops.entity.OpsMoneyRequest;
import org.coryd.payment.ops.entity.OpsRequestStatus;
import org.coryd.payment.ops.entity.OpsRequestType;

import java.math.BigDecimal;
import java.time.Instant;

/** One ops money action and, for the admin looking at it, what they may do with it. */
public record OpsMoneyRequestView(String id, OpsRequestType type, OpsRequestStatus status, BigDecimal amount,
                                  OpsMoneyRequest.Direction direction, String bookingId, String riderId, String driverId,
                                  String targetId, String reason, boolean needsApproval, BigDecimal approvalThreshold,
                                  Person requestedBy, Instant requestedAt, Person decidedBy, String decisionReason,
                                  Instant decidedAt, Instant executedAt, String failureReason, String refundId, int attempts,
                                  Actions actions) {

    public record Actions(boolean approve, boolean reject, boolean cancel, boolean retry) {
    }

    public static OpsMoneyRequestView of(OpsMoneyRequest r, String viewerId, BigDecimal threshold) {
        boolean pending = r.getStatus() == OpsRequestStatus.PENDING_APPROVAL;
        boolean mine = r.getRequestedBy().equals(viewerId);
        boolean retryable = r.getStatus() == OpsRequestStatus.FAILED && r.getType() != OpsRequestType.DEBT_ADJUSTMENT;
        return new OpsMoneyRequestView(r.getId(), r.getType(), r.getStatus(), r.getAmount(), r.getDirection(), r.getBookingId(),
                r.getRiderId(), r.getDriverId(), r.getTargetId(), r.getReason(), r.isNeedsApproval(), threshold,
                new Person(r.getRequestedBy(), r.getRequestedByName()), r.getCreatedAt(),
                r.getDecidedBy() == null ? null : new Person(r.getDecidedBy(), r.getDecidedByName()),
                r.getDecisionReason(), r.getDecidedAt(), r.getExecutedAt(), r.getFailureReason(), r.getRefundId(), r.getAttempts(),
                new Actions(pending && !mine, pending && !mine, pending && mine, retryable));
    }
}
