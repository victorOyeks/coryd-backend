package org.coryd.payment.ops.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.exception.BadRequestException;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.common.response.PagedResponse;
import org.coryd.payment.dto.RefundKind;
import org.coryd.payment.dto.RefundStatus;
import org.coryd.payment.entity.FareHold;
import org.coryd.payment.entity.PaymentTransaction;
import org.coryd.payment.ops.OpsMoneyProperties;
import org.coryd.payment.ops.dto.OpsMoneyRequestView;
import org.coryd.payment.ops.dto.RefundFailureItem;
import org.coryd.payment.ops.dto.RetryRequest;
import org.coryd.payment.ops.entity.OpsMoneyAudit;
import org.coryd.payment.ops.entity.OpsMoneyRequest;
import org.coryd.payment.ops.entity.OpsRequestStatus;
import org.coryd.payment.ops.entity.OpsRequestType;
import org.coryd.payment.ops.repository.OpsMoneyLockRepository;
import org.coryd.payment.ops.repository.OpsMoneyQueryRepository;
import org.coryd.payment.ops.repository.OpsMoneyRequestRepository;
import org.coryd.payment.repository.FareHoldRepository;
import org.coryd.payment.repository.PaymentTransactionRepository;
import org.coryd.payment.repository.RefundRepository;
import org.coryd.payment.service.EarningsService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Refunds that haven't reached the rider: cancellation refunds Paystack refused (on the automatic 15-minute retry until it
 * takes them), refunds stuck PENDING with nothing retrying them, and ops refunds Paystack turned down. A cancellation refund
 * can be retried now by hand - through the two-person rule above the threshold - using the same retry the job runs.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpsRefundFailureService implements OpsMoneyExecutor {

    private static final List<OpsRequestStatus> OPEN = List.of(OpsRequestStatus.PENDING_APPROVAL, OpsRequestStatus.EXECUTING);

    private final FareHoldRepository fareHoldRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final RefundRepository refundRepository;
    private final OpsMoneyLockRepository lockRepository;
    private final OpsMoneyRequestRepository requestRepository;
    private final OpsMoneyQueryRepository queryRepository;
    private final EarningsService earningsService;
    private final OpsRequestSupport support;
    private final OpsMoneyAuditService audit;
    private final OpsMoneyProperties properties;

    @Override
    public OpsRequestType type() {
        return OpsRequestType.REFUND_RETRY;
    }

    @Transactional(readOnly = true)
    public PagedResponse<RefundFailureItem> list(RefundFailureItem.Kind kind, int page, int size) {
        List<RefundFailureItem> items = new ArrayList<>();
        if (kind == null || kind == RefundFailureItem.Kind.CANCELLATION) {
            for (FareHold hold : fareHoldRepository.findAllById(fareHoldRepository.findIdsWithRefundDue())) {
                PaymentTransaction payment = paymentTransactionRepository.findFirstByBookingIdOrderByCreatedAtDesc(hold.getBookingId()).orElse(null);
                String open = requestRepository.findByTargetIdOrderByCreatedAtAsc(hold.getId()).stream()
                        .filter(r -> r.getType() == OpsRequestType.REFUND_RETRY && r.getStatus().open())
                        .map(OpsMoneyRequest::getId).findFirst().orElse(null);
                items.add(new RefundFailureItem(RefundFailureItem.Kind.CANCELLATION, hold.getId(), hold.getBookingId(),
                        payment == null ? null : payment.getRiderId(), hold.getDriverId(), hold.getRefundDue(), hold.getRefundReason(),
                        hold.getUpdatedAt(), "Paystack has not accepted this refund yet - retried automatically every 15 minutes",
                        true, open == null, open, payment == null ? null : payment.getGatewayReference()));
            }
        }
        if (kind == null || kind == RefundFailureItem.Kind.STUCK) {
            Instant cutoff = Instant.now().minus(Duration.ofMinutes(properties.getStuckRefundMinutes()));
            for (Object[] r : queryRepository.stuckPendingRefunds(cutoff)) {
                items.add(new RefundFailureItem(RefundFailureItem.Kind.STUCK, (String) r[0], (String) r[1], (String) r[2], (String) r[3],
                        (BigDecimal) r[4], (String) r[5], OpsMoneyQueryRepository.instant(r[6]),
                        "Pending with no automatic retry - settle it on the Paystack dashboard", false, false, null, null));
            }
        }
        if (kind == null || kind == RefundFailureItem.Kind.MANUAL) {
            for (OpsMoneyRequest r : requestRepository.findByTypeAndStatusOrderByCreatedAtDesc(OpsRequestType.MANUAL_REFUND, OpsRequestStatus.FAILED)) {
                items.add(new RefundFailureItem(RefundFailureItem.Kind.MANUAL, r.getId(), r.getBookingId(), r.getRiderId(), r.getDriverId(),
                        r.getAmount(), r.getReason(), r.getExecutedAt() == null ? r.getUpdatedAt() : r.getExecutedAt(), r.getFailureReason(),
                        false, true, null, null));
            }
        }
        items.sort(Comparator.comparing(RefundFailureItem::failedSince, Comparator.nullsLast(Comparator.reverseOrder())));
        int from = Math.min(page * size, items.size());
        int to = Math.min(from + size, items.size());
        int totalPages = (int) Math.ceil(items.size() / (double) size);
        return new PagedResponse<>(items.subList(from, to), page, size, items.size(), totalPages, to >= items.size());
    }

    /** A cancellation refund Paystack refused, asked again now - or queued for a second admin above the threshold. */
    @Transactional
    public OpsMoneyRequestView retryCancellation(String holdId, RetryRequest body, OpsActor actor) {
        String reason = OpsRequestSupport.requireReason(body.reason());
        FareHold hold = lockRepository.lockHold(holdId).orElseThrow(() -> new ResourceNotFoundException("No such refund"));
        var replay = support.replay(body.idempotencyKey(), OpsRequestType.REFUND_RETRY, holdId);
        if (replay.isPresent()) {
            return support.view(replay.get(), actor);
        }
        if (hold.getRefundDue().signum() <= 0) {
            throw new BadRequestException("This refund isn't waiting on a retry - it has already gone through");
        }
        if (requestRepository.existsByTargetIdAndTypeAndStatusIn(holdId, OpsRequestType.REFUND_RETRY, OPEN)) {
            throw new BadRequestException("A retry of this refund is already waiting for approval");
        }
        String riderId = paymentTransactionRepository.findFirstByBookingIdOrderByCreatedAtDesc(hold.getBookingId())
                .map(PaymentTransaction::getRiderId).orElse(null);
        OpsMoneyRequest request = support.create(OpsMoneyRequest.builder()
                .type(OpsRequestType.REFUND_RETRY)
                .bookingId(hold.getBookingId())
                .riderId(riderId)
                .driverId(hold.getDriverId())
                .targetId(holdId), hold.getRefundDue(), reason, body.idempotencyKey(), actor);
        audit.record(actor, OpsMoneyAudit.Action.REFUND_RETRY_REQUESTED, OpsMoneyAudit.TargetType.FARE_HOLD, holdId, request.getId(),
                request.getAmount(), Map.of("refundDue", hold.getRefundDue()), Map.of("status", request.getStatus()), reason);
        if (request.getStatus() == OpsRequestStatus.PENDING_APPROVAL) {
            return support.view(request, actor);
        }
        return support.view(execute(request, actor), actor);
    }

    /** EarningsService.retryRefund is the job's own unit of work - it asks Paystack, and on its yes writes the refund once. */
    @Override
    public OpsMoneyRequest execute(OpsMoneyRequest request, OpsActor actor) {
        String holdId = request.getTargetId();
        FareHold hold = lockRepository.lockHold(holdId).orElseThrow(() -> new ResourceNotFoundException("No such refund"));
        BigDecimal due = hold.getRefundDue();
        String bookingId = hold.getBookingId();
        if (due.signum() <= 0) {
            throw new BadRequestException("This refund has already gone through - nothing to retry");
        }
        request.setAttempts(request.getAttempts() + 1);
        requestRepository.saveAndFlush(request);
        String requestId = request.getId();

        earningsService.retryRefund(holdId);

        FareHold after = fareHoldRepository.findById(holdId).orElseThrow();
        boolean stillPending = refundRepository.findByBookingId(bookingId).stream()
                .anyMatch(r -> r.getKind() == RefundKind.CANCELLATION && r.getStatus() == RefundStatus.PENDING);
        OpsMoneyRequest current = requestRepository.findById(requestId).orElseThrow();
        current.setExecutedAt(Instant.now());
        OpsMoneyAudit.Action action;
        if (after.getRefundDue().signum() > 0) {
            current.setStatus(OpsRequestStatus.FAILED);
            current.setFailureReason("Paystack still did not accept the refund - it stays on the automatic retry");
            action = OpsMoneyAudit.Action.REFUND_RETRY_FAILED;
        } else if (stillPending) {
            current.setStatus(OpsRequestStatus.FAILED);
            current.setFailureReason("Paystack could not refund this charge - settle it by hand on the Paystack dashboard");
            action = OpsMoneyAudit.Action.REFUND_RETRY_FAILED;
        } else {
            current.setStatus(OpsRequestStatus.COMPLETED);
            current.setFailureReason(null);
            action = OpsMoneyAudit.Action.REFUND_RETRIED;
        }
        OpsMoneyRequest saved = requestRepository.save(current);
        audit.record(actor, action, OpsMoneyAudit.TargetType.FARE_HOLD, holdId, saved.getId(), due, Map.of("refundDue", due),
                Map.of("refundDue", after.getRefundDue(), "status", saved.getStatus()), saved.getReason());
        return saved;
    }
}
