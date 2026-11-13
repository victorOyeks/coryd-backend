package org.coryd.payment.ops.service;

import lombok.RequiredArgsConstructor;
import org.coryd.common.exception.BadRequestException;
import org.coryd.payment.ops.OpsMoneyProperties;
import org.coryd.payment.ops.dto.OpsMoneyRequestView;
import org.coryd.payment.ops.entity.OpsMoneyRequest;
import org.coryd.payment.ops.entity.OpsRequestStatus;
import org.coryd.payment.ops.entity.OpsRequestType;
import org.coryd.payment.ops.repository.OpsMoneyRequestRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.Optional;

/** What every ops money action shares: a reason, an amount in naira and kobo, idempotency, and the request row itself. */
@Component
@RequiredArgsConstructor
public class OpsRequestSupport {

    private final OpsMoneyRequestRepository requestRepository;
    private final OpsMoneyProperties properties;

    public static String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BadRequestException("A reason is required");
        }
        String trimmed = reason.trim();
        if (trimmed.length() > 500) {
            throw new BadRequestException("The reason can be at most 500 characters");
        }
        return trimmed;
    }

    /** Naira with at most two decimals (kobo), more than zero. */
    public static BigDecimal amount(BigDecimal value) {
        if (value == null || value.signum() <= 0) {
            throw new BadRequestException("The amount must be more than zero");
        }
        try {
            return value.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new BadRequestException("The amount can have at most two decimal places (kobo)");
        }
    }

    public static String naira(BigDecimal amount) {
        DecimalFormat format = new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(Locale.ENGLISH));
        return "₦" + format.format(amount);
    }

    /** A resend of the same submit returns the first result; the same key on a different action is refused. */
    public Optional<OpsMoneyRequest> replay(String idempotencyKey, OpsRequestType type, String targetId) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }
        return requestRepository.findByIdempotencyKey(idempotencyKey.trim()).map(existing -> {
            String existingTarget = existing.getType() == OpsRequestType.MANUAL_REFUND ? existing.getBookingId()
                    : existing.getType() == OpsRequestType.DEBT_ADJUSTMENT ? existing.getDriverId() : existing.getTargetId();
            if (existing.getType() != type || !targetId.equals(existingTarget)) {
                throw new BadRequestException("This idempotencyKey was already used for a different action");
            }
            return existing;
        });
    }

    public OpsMoneyRequest create(OpsMoneyRequest.OpsMoneyRequestBuilder<?, ?> builder, BigDecimal amount, String reason,
                                  String idempotencyKey, OpsActor actor) {
        boolean needsApproval = properties.needsApproval(amount);
        return requestRepository.saveAndFlush(builder
                .amount(amount)
                .reason(reason)
                .idempotencyKey(idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey.trim())
                .needsApproval(needsApproval)
                .status(needsApproval ? OpsRequestStatus.PENDING_APPROVAL : OpsRequestStatus.EXECUTING)
                .requestedBy(actor.id())
                .requestedByName(actor.name())
                .build());
    }

    public OpsMoneyRequestView view(OpsMoneyRequest request, OpsActor viewer) {
        return OpsMoneyRequestView.of(request, viewer.id(), properties.getApprovalThreshold());
    }

    public BigDecimal threshold() {
        return properties.getApprovalThreshold();
    }

    /** Kafka only once the money action has committed - a rolled-back refund must not tell the rider it happened. */
    public static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
