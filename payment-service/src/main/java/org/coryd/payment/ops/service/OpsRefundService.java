package org.coryd.payment.ops.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.enums.PaymentMethod;
import org.coryd.common.enums.PayoutStatus;
import org.coryd.common.exception.BadRequestException;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.common.kafka.KafkaTopics;
import org.coryd.common.kafka.events.RefundIssuedEvent;
import org.coryd.payment.dto.RefundKind;
import org.coryd.payment.dto.RefundStatus;
import org.coryd.payment.entity.FareHold;
import org.coryd.payment.entity.PaymentTransaction;
import org.coryd.payment.entity.Refund;
import org.coryd.payment.gateway.PaymentGatewayClient;
import org.coryd.payment.ops.dto.ManualRefundRequest;
import org.coryd.payment.ops.dto.OpsMoneyRequestView;
import org.coryd.payment.ops.dto.RefundableView;
import org.coryd.payment.ops.entity.OpsMoneyAudit;
import org.coryd.payment.ops.entity.OpsMoneyRequest;
import org.coryd.payment.ops.entity.OpsRequestStatus;
import org.coryd.payment.ops.entity.OpsRequestType;
import org.coryd.payment.ops.repository.OpsMoneyLockRepository;
import org.coryd.payment.ops.repository.OpsMoneyRequestRepository;
import org.coryd.payment.repository.FareHoldRepository;
import org.coryd.payment.repository.PaymentTransactionRepository;
import org.coryd.payment.repository.RefundRepository;
import org.coryd.payment.service.EarningsService;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Manual refunds from the Ops Console: full or partial, never more than the booking's payment still has to give back.
 * Every refund on a booking - asking, approving, running - first takes a row lock on that booking's payment, so two admins
 * (or one double-click) can never both refund the same naira. The money goes back through the same door as every other
 * refund: Paystack's refund on the charge, a refunds row, the driver's share recorded against them, the rider told.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpsRefundService implements OpsMoneyExecutor {

    /** Refunds of this booking's own payment. A duplicate charge was a separate charge; an overpayment is a driver's. */
    private static final Set<RefundKind> OF_THIS_PAYMENT = Set.of(RefundKind.CANCELLATION);

    private final OpsMoneyLockRepository lockRepository;
    private final OpsMoneyRequestRepository requestRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final FareHoldRepository fareHoldRepository;
    private final RefundRepository refundRepository;
    private final PaymentGatewayClient gatewayClient;
    private final EarningsService earningsService;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final OpsRequestSupport support;
    private final OpsMoneyAuditService audit;

    @Override
    public OpsRequestType type() {
        return OpsRequestType.MANUAL_REFUND;
    }

    @Transactional(readOnly = true)
    public RefundableView refundable(String bookingId) {
        PaymentTransaction payment = paymentTransactionRepository.findFirstByBookingIdOrderByCreatedAtDesc(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("No payment found for this booking"));
        return refundable(payment, null);
    }

    /**
     * paid less what's gone back or is on its way (refunds of this payment, REFUNDED or PENDING) less what open ops
     * requests have already claimed. Every figure is capped so refunded never reads more than paid - whatever the rows say.
     */
    RefundableView refundable(PaymentTransaction payment, String excludeRequestId) {
        String bookingId = payment.getBookingId();
        boolean paid = payment.getStatus() == PayoutStatus.PAID;
        BigDecimal amountPaid = paid ? payment.getAmount() : BigDecimal.ZERO;
        List<Refund> refunds = refundRepository.findByBookingId(bookingId);
        BigDecimal refunded = sum(refunds, RefundStatus.REFUNDED, true);
        BigDecimal pending = sum(refunds, RefundStatus.PENDING, true);
        BigDecimal duplicates = refunds.stream().filter(r -> r.getKind() == RefundKind.DUPLICATE_CHARGE && r.getStatus() == RefundStatus.REFUNDED)
                .map(Refund::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal reserved = requestRepository.reservedForBooking(bookingId, excludeRequestId);

        BigDecimal shownRefunded = refunded.min(amountPaid);
        BigDecimal shownPending = pending.min(amountPaid.subtract(shownRefunded));
        BigDecimal shownReserved = reserved.min(amountPaid.subtract(shownRefunded).subtract(shownPending));
        BigDecimal refundable = amountPaid.subtract(shownRefunded).subtract(shownPending).subtract(shownReserved).max(BigDecimal.ZERO);
        FareHold hold = fareHoldRepository.findFirstByBookingIdOrderByCreatedAtDesc(bookingId).orElse(null);
        // B-524EA3: the hold was settled as refunded (the booking was cancelled and the fare marked as gone back) but no refund
        // of this payment is on record - only, say, a duplicate-charge refund. The money may already be with the rider through
        // a path these rows don't show, so offering the fare again risks paying it twice. Nothing is refundable until someone
        // has checked Paystack (and recorded what they found).
        String blockedReason = null;
        if (paid && hold != null && hold.isRefunded() && refunded.add(pending).signum() == 0) {
            blockedReason = RefundableView.FARE_ALREADY_RETURNED;
            refundable = BigDecimal.ZERO;
        }
        if (refunded.add(pending).compareTo(amountPaid) > 0 && paid) {
            log.error("RECONCILE: booking {} has {} refunded/pending against {} paid on {}", bookingId, refunded.add(pending),
                    amountPaid, payment.getGatewayReference());
        }

        return new RefundableView(bookingId, payment.getId(), payment.getGatewayReference(), payment.getStatus().name(), amountPaid,
                shownRefunded, shownPending, shownReserved, refundable, duplicates, support.threshold(), payment.getRiderId(),
                hold == null ? null : hold.getDriverId(), hold == null || hold.isReleased() || hold.isRefunded(), blockedReason);
    }

    private static BigDecimal sum(List<Refund> refunds, RefundStatus status, boolean ofThisPayment) {
        return refunds.stream()
                .filter(r -> r.getStatus() == status && (!ofThisPayment || OF_THIS_PAYMENT.contains(r.getKind())))
                .map(Refund::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** At or below the threshold it runs now; above it, it waits for a second admin and nothing is sent yet. */
    @Transactional
    public OpsMoneyRequestView submit(String bookingId, ManualRefundRequest body, OpsActor actor) {
        String reason = OpsRequestSupport.requireReason(body.reason());
        PaymentTransaction payment = lockRepository.lockPaymentForBooking(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("No payment found for this booking"));
        // After the lock, so a resend racing the first submit sees what it did.
        var replay = support.replay(body.idempotencyKey(), OpsRequestType.MANUAL_REFUND, bookingId);
        if (replay.isPresent()) {
            return support.view(replay.get(), actor);
        }
        requireRefundablePayment(payment);
        RefundableView before = refundable(payment, null);
        if (before.blockedReason() != null) {
            throw new BadRequestException(before.blockedReason());
        }
        if (before.refundable().signum() <= 0) {
            throw new BadRequestException("Nothing is left to refund on this booking - " + OpsRequestSupport.naira(before.paid())
                    + " paid, " + OpsRequestSupport.naira(before.refunded().add(before.refundPending()).add(before.awaitingApproval()))
                    + " already refunded or awaiting approval");
        }
        BigDecimal amount = body.amount() == null ? before.refundable() : OpsRequestSupport.amount(body.amount());
        if (amount.compareTo(before.refundable()) > 0) {
            throw new BadRequestException("Only " + OpsRequestSupport.naira(before.refundable()) + " can still be refunded on this booking");
        }

        OpsMoneyRequest request = support.create(OpsMoneyRequest.builder()
                .type(OpsRequestType.MANUAL_REFUND)
                .bookingId(bookingId)
                .riderId(payment.getRiderId())
                .driverId(before.driverId())
                .targetId(payment.getId()), amount, reason, body.idempotencyKey(), actor);
        audit.record(actor, OpsMoneyAudit.Action.REFUND_REQUESTED, OpsMoneyAudit.TargetType.BOOKING, bookingId, request.getId(),
                amount, before, Map.of("status", request.getStatus()), reason);
        if (request.getStatus() == OpsRequestStatus.PENDING_APPROVAL) {
            return support.view(request, actor);
        }
        return support.view(execute(request, actor), actor);
    }

    /**
     * Re-checks what's refundable under the lock (an approval can come days later), asks Paystack, and only on its yes
     * writes the refund. A refusal leaves the request FAILED with nothing written, to be retried from Refund failures.
     */
    @Override
    public OpsMoneyRequest execute(OpsMoneyRequest request, OpsActor actor) {
        String bookingId = request.getBookingId();
        PaymentTransaction payment = lockRepository.lockPaymentForBooking(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("No payment found for this booking"));
        requireRefundablePayment(payment);
        RefundableView before = refundable(payment, request.getId());
        if (before.blockedReason() != null) {
            throw new BadRequestException(before.blockedReason());
        }
        BigDecimal amount = request.getAmount();
        if (amount.compareTo(before.refundable()) > 0) {
            throw new BadRequestException("Only " + OpsRequestSupport.naira(before.refundable())
                    + " can still be refunded on this booking - this request is for " + OpsRequestSupport.naira(amount));
        }
        String reference = payment.getGatewayReference();
        String riderId = payment.getRiderId();
        String paymentId = payment.getId();
        request.setAttempts(request.getAttempts() + 1);

        boolean accepted;
        try {
            accepted = gatewayClient.refund(reference, amount, request.getReason());
        } catch (Exception e) {
            log.error("Paystack refund call failed for booking {}: {}", bookingId, e.getMessage());
            accepted = false;
        }
        if (!accepted) {
            request.setStatus(OpsRequestStatus.FAILED);
            request.setFailureReason("Paystack did not accept the refund - nothing was sent. Retry it from Refund failures.");
            request.setExecutedAt(Instant.now());
            OpsMoneyRequest saved = requestRepository.save(request);
            audit.record(actor, OpsMoneyAudit.Action.REFUND_FAILED, OpsMoneyAudit.TargetType.BOOKING, bookingId, saved.getId(),
                    amount, before, Map.of("status", saved.getStatus()), saved.getFailureReason());
            log.error("RECONCILE: ops refund {} of {} on booking {} refused by Paystack", saved.getId(), amount, bookingId);
            return saved;
        }

        Refund refund = refundRepository.save(Refund.builder()
                .userId(riderId)
                .bookingId(bookingId)
                .paymentTransactionId(paymentId)
                .amount(amount)
                .status(RefundStatus.REFUNDED)
                // CANCELLATION, not a new kind: the rider app's payment history only knows CANCELLATION and DUPLICATE_CHARGE.
                // That it was an ops refund is on the ops_money_requests row that points at it.
                .kind(RefundKind.CANCELLATION)
                .reason(OpsMoneyAuditService.truncate(request.getReason(), 255))
                .refundedAt(Instant.now())
                .build());
        request.setRefundId(refund.getId());
        request.setStatus(OpsRequestStatus.COMPLETED);
        request.setExecutedAt(Instant.now());
        request.setFailureReason(null);
        OpsMoneyRequest saved = requestRepository.saveAndFlush(request);

        settleHold(bookingId, amount);
        // Recorded with the same rule as every other refund: the driver was split their share at charge time.
        RefundableView after = refundable(paymentTransactionRepository.findById(paymentId).orElseThrow(), null);
        audit.record(actor, OpsMoneyAudit.Action.REFUND_EXECUTED, OpsMoneyAudit.TargetType.BOOKING, bookingId, saved.getId(),
                amount, before, after, saved.getReason());
        String reason = saved.getReason();
        OpsRequestSupport.afterCommit(() -> kafkaTemplate.send(KafkaTopics.REFUND_ISSUED, RefundIssuedEvent.builder()
                .bookingId(bookingId)
                .riderId(riderId)
                .amount(amount)
                .reason(reason)
                .refundedAt(LocalDateTime.now())
                .build()));
        log.info("OPS refund {} of {} on booking {} accepted by Paystack", saved.getId(), amount, bookingId);
        return saved;
    }

    /**
     * The fare hold is what a later cancellation refunds and a completion credits. Refunding all that's left of a seat not
     * yet settled settles it as refunded, so neither happens again; a part leaves the rest on it. The driver owes their
     * share of what went back either way - the fare's debt recovery counted once, with the refund that ends the seat.
     */
    private void settleHold(String bookingId, BigDecimal amount) {
        FareHold hold = fareHoldRepository.findFirstByBookingIdOrderByCreatedAtDesc(bookingId).orElse(null);
        if (hold == null) {
            log.warn("RECONCILE: ops refund on booking {} has no fare hold - driver's share not recorded", bookingId);
            return;
        }
        String driverId = hold.getDriverId();
        BigDecimal debtRecovery = BigDecimal.ZERO;
        if (!hold.isReleased() && !hold.isRefunded()) {
            if (amount.compareTo(hold.getAmount()) >= 0) {
                if (fareHoldRepository.markRefunded(hold.getId()) == 1) {
                    debtRecovery = hold.getDebtRecovery();
                }
            } else {
                lockRepository.reduceHoldAmount(hold.getId(), amount);
            }
        }
        earningsService.chargeRefundToDriver(driverId, bookingId, amount, debtRecovery);
    }

    private static void requireRefundablePayment(PaymentTransaction payment) {
        if (payment.getStatus() != PayoutStatus.PAID) {
            throw new BadRequestException("This booking's payment hasn't succeeded (" + payment.getStatus() + ") - there is nothing to refund");
        }
        if (payment.getPaymentMethod() != PaymentMethod.PAYSTACK) {
            throw new BadRequestException("This booking wasn't paid by card - there is no card to refund to");
        }
    }
}
