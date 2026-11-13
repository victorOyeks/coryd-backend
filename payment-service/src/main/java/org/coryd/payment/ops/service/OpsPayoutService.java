package org.coryd.payment.ops.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.exception.BadRequestException;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.common.response.PagedResponse;
import org.coryd.payment.dto.SettlementStatus;
import org.coryd.payment.entity.DriverSettlement;
import org.coryd.payment.entity.PayoutRecipient;
import org.coryd.payment.gateway.PaystackGatewayClient;
import org.coryd.payment.ops.dto.OpsMoneyRequestView;
import org.coryd.payment.ops.dto.PayoutAccountView;
import org.coryd.payment.ops.dto.PayoutDetail;
import org.coryd.payment.ops.dto.PayoutItem;
import org.coryd.payment.ops.dto.Person;
import org.coryd.payment.ops.dto.RetryRequest;
import org.coryd.payment.ops.dto.TimelineEntry;
import org.coryd.payment.ops.dto.TransactionFilter;
import org.coryd.payment.ops.dto.TransactionRow;
import org.coryd.payment.ops.dto.TransactionType;
import org.coryd.payment.ops.entity.OpsMoneyAudit;
import org.coryd.payment.ops.entity.OpsMoneyRequest;
import org.coryd.payment.ops.entity.OpsRequestStatus;
import org.coryd.payment.ops.entity.OpsRequestType;
import org.coryd.payment.ops.repository.OpsMoneyAuditRepository;
import org.coryd.payment.ops.repository.OpsMoneyLockRepository;
import org.coryd.payment.ops.repository.OpsMoneyQueryRepository;
import org.coryd.payment.ops.repository.OpsMoneyRequestRepository;
import org.coryd.payment.repository.DriverSettlementRepository;
import org.coryd.payment.repository.PayoutRecipientRepository;
import org.coryd.payment.service.OpsSettlementResync;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Driver payouts are Paystack settlements from each driver's subaccount to their bank, mirrored by SettlementSyncService.
 * Paystack has no "settle again" call: a failed settlement is re-attempted by Paystack once the bank account is good. So a
 * retry here checks the driver's default account with the bank through Paystack (a bad or renamed account fails the retry
 * with that reason, for the driver to fix in the app), re-reads the driver's settlements from Paystack, and records that
 * ops retried it - two admins above the threshold, like a refund.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpsPayoutService implements OpsMoneyExecutor {

    private static final List<OpsRequestStatus> OPEN = List.of(OpsRequestStatus.PENDING_APPROVAL, OpsRequestStatus.EXECUTING);

    private final OpsMoneyQueryRepository queryRepository;
    private final OpsMoneyRequestRepository requestRepository;
    private final OpsMoneyAuditRepository auditRepository;
    private final OpsMoneyLockRepository lockRepository;
    private final DriverSettlementRepository settlementRepository;
    private final PayoutRecipientRepository payoutRecipientRepository;
    private final PaystackGatewayClient paystackGatewayClient;
    private final OpsSettlementResync settlementResync;
    private final OpsRequestSupport support;
    private final OpsMoneyAuditService audit;

    @Override
    public OpsRequestType type() {
        return OpsRequestType.PAYOUT_RETRY;
    }

    @Transactional(readOnly = true)
    public PagedResponse<PayoutItem> list(TransactionFilter filter, int page, int size) {
        TransactionFilter payouts = filter.withType(TransactionType.PAYOUT);
        List<TransactionRow> rows = queryRepository.transactions(payouts, page, size);
        long total = queryRepository.countTransactions(payouts);
        Map<String, DriverSettlement> settlements = settlementRepository.findAllById(rows.stream().map(TransactionRow::id).toList())
                .stream().collect(Collectors.toMap(DriverSettlement::getId, s -> s));
        Map<String, String> openRetries = openRetries(rows.stream().map(TransactionRow::id).toList());
        List<PayoutItem> items = rows.stream().map(r -> item(settlements.get(r.id()), openRetries.get(r.id()))).filter(Objects::nonNull).toList();
        int totalPages = (int) Math.ceil(total / (double) size);
        return new PagedResponse<>(items, page, size, total, totalPages, (long) (page + 1) * size >= total);
    }

    @Transactional(readOnly = true)
    public Map<String, Long> counts(TransactionFilter filter) {
        Map<String, Long> counts = queryRepository.payoutCountsByStatus(filter);
        Map<String, Long> withAll = new LinkedHashMap<>();
        withAll.put("all", counts.values().stream().mapToLong(Long::longValue).sum());
        counts.forEach((k, v) -> withAll.put(k.toLowerCase(Locale.ROOT), v));
        return withAll;
    }

    @Transactional(readOnly = true)
    public PayoutDetail detail(String id, OpsActor viewer) {
        DriverSettlement s = settlementRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("No such payout"));
        List<OpsMoneyRequest> retries = requestRepository.findByTargetIdOrderByCreatedAtAsc(id).stream()
                .filter(r -> r.getType() == OpsRequestType.PAYOUT_RETRY).toList();
        String open = retries.stream().filter(r -> r.getStatus().open()).map(OpsMoneyRequest::getId).findFirst().orElse(null);
        List<TimelineEntry> timeline = new ArrayList<>();
        timeline.add(new TimelineEntry(s.getCreatedAt(), "FIRST_SEEN", s.getAmount(), "Settlement first synced from Paystack", null));
        if (s.getSettlementDate() != null) {
            timeline.add(new TimelineEntry(s.getSettlementDate(), "SETTLEMENT_" + status(s), s.getAmount(), "Paystack settlement date", null));
        }
        auditRepository.findByTargetIdOrderByCreatedAtAsc(id).forEach(a -> timeline.add(new TimelineEntry(a.getCreatedAt(),
                a.getAction().name(), a.getAmount(), a.getReason(), new Person(a.getActorId(), a.getActorName()))));
        timeline.sort(Comparator.comparing(TimelineEntry::at, Comparator.nullsLast(Comparator.naturalOrder())));
        return new PayoutDetail(item(s, open), account(s.getDriverId(), false),
                retries.stream().map(r -> support.view(r, viewer)).toList(), timeline);
    }

    @Transactional(readOnly = true)
    public PayoutAccountView payoutAccount(String driverId) {
        return account(driverId, false);
    }

    /** The full account number, once, recorded against the admin who asked for it. */
    @Transactional
    public PayoutAccountView.Account reveal(String driverId, String recipientId, String reason, OpsActor actor) {
        String why = OpsRequestSupport.requireReason(reason);
        List<PayoutRecipient> accounts = payoutRecipientRepository.findByDriverIdOrderByCreatedAtDesc(driverId);
        PayoutRecipient recipient = accounts.stream()
                .filter(a -> recipientId == null || recipientId.isBlank() ? a.isDefault() : a.getId().equals(recipientId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("This driver has no such payout account"));
        audit.record(actor, OpsMoneyAudit.Action.PAYOUT_ACCOUNT_REVEALED, OpsMoneyAudit.TargetType.PAYOUT_ACCOUNT, recipient.getId(), null,
                null, Map.of("driverId", driverId, "accountNumber", mask(recipient.getAccountNumber())), null, why);
        return toAccount(recipient, true);
    }

    @Transactional
    public OpsMoneyRequestView retry(String settlementId, RetryRequest body, OpsActor actor) {
        String reason = OpsRequestSupport.requireReason(body.reason());
        DriverSettlement s = lockRepository.lockSettlement(settlementId).orElseThrow(() -> new ResourceNotFoundException("No such payout"));
        var replay = support.replay(body.idempotencyKey(), OpsRequestType.PAYOUT_RETRY, settlementId);
        if (replay.isPresent()) {
            return support.view(replay.get(), actor);
        }
        if (SettlementStatus.fromGateway(s.getStatus()) != SettlementStatus.FAILED) {
            throw new BadRequestException("Only a failed payout can be retried - this one is " + status(s));
        }
        if (requestRepository.existsByTargetIdAndTypeAndStatusIn(settlementId, OpsRequestType.PAYOUT_RETRY, OPEN)) {
            throw new BadRequestException("A retry of this payout is already waiting for approval");
        }
        OpsMoneyRequest request = support.create(OpsMoneyRequest.builder()
                .type(OpsRequestType.PAYOUT_RETRY)
                .driverId(s.getDriverId())
                .targetId(settlementId), s.getAmount(), reason, body.idempotencyKey(), actor);
        audit.record(actor, OpsMoneyAudit.Action.PAYOUT_RETRY_REQUESTED, OpsMoneyAudit.TargetType.SETTLEMENT, settlementId, request.getId(),
                s.getAmount(), Map.of("status", status(s)), Map.of("status", request.getStatus()), reason);
        if (request.getStatus() == OpsRequestStatus.PENDING_APPROVAL) {
            return support.view(request, actor);
        }
        return support.view(execute(request, actor), actor);
    }

    @Override
    public OpsMoneyRequest execute(OpsMoneyRequest request, OpsActor actor) {
        DriverSettlement s = lockRepository.lockSettlement(request.getTargetId())
                .orElseThrow(() -> new ResourceNotFoundException("No such payout"));
        String before = status(s);
        if (SettlementStatus.fromGateway(s.getStatus()) != SettlementStatus.FAILED) {
            throw new BadRequestException("This payout is no longer failed - it is " + before);
        }
        request.setAttempts(request.getAttempts() + 1);
        request.setExecutedAt(Instant.now());
        String failure = checkAccount(s.getDriverId());
        if (failure == null) {
            settlementResync.resync(s.getDriverId());
        }
        String after = settlementRepository.findById(s.getId()).map(OpsPayoutService::status).orElse(before);
        request.setStatus(failure == null ? OpsRequestStatus.COMPLETED : OpsRequestStatus.FAILED);
        request.setFailureReason(failure);
        OpsMoneyRequest saved = requestRepository.save(request);
        audit.record(actor, failure == null ? OpsMoneyAudit.Action.PAYOUT_RETRIED : OpsMoneyAudit.Action.PAYOUT_RETRY_FAILED,
                OpsMoneyAudit.TargetType.SETTLEMENT, s.getId(), saved.getId(), s.getAmount(), Map.of("status", before),
                Map.of("status", after, "note", failure == null
                        ? "Account verified with the bank; Paystack re-attempts failed settlements on its next cycle"
                        : failure), saved.getReason());
        return saved;
    }

    /** Null when the driver's default account checks out with the bank, otherwise why it doesn't. */
    private String checkAccount(String driverId) {
        PayoutRecipient recipient = payoutRecipientRepository.findFirstByDriverIdAndIsDefaultTrueAndGatewaySubaccountCodeIsNotNull(driverId)
                .orElse(null);
        if (recipient == null) {
            return "The driver has no payout account linked to Paystack - they need to add one in the app";
        }
        try {
            PaystackGatewayClient.ResolvedAccount resolved = paystackGatewayClient.resolveAccountNumber(recipient.getAccountNumber(),
                    recipient.getBankCode());
            if (resolved == null) {
                return "The bank did not confirm the driver's account - they should check it in the app";
            }
            if (resolved.accountName() != null && recipient.getAccountName() != null
                    && !normalise(resolved.accountName()).equals(normalise(recipient.getAccountName()))) {
                return "The bank now names this account '" + resolved.accountName() + "', not '" + recipient.getAccountName()
                        + "' - ask the driver to re-add it";
            }
            return null;
        } catch (Exception e) {
            return "The bank could not confirm the driver's account (" + e.getMessage() + ") - they should check it in the app";
        }
    }

    private Map<String, String> openRetries(List<String> settlementIds) {
        if (settlementIds.isEmpty()) {
            return Map.of();
        }
        return requestRepository.findByTargetIdIn(settlementIds).stream()
                .filter(r -> r.getType() == OpsRequestType.PAYOUT_RETRY && r.getStatus().open())
                .collect(Collectors.toMap(OpsMoneyRequest::getTargetId, OpsMoneyRequest::getId, (a, b) -> a));
    }

    private PayoutItem item(DriverSettlement s, String openRetry) {
        if (s == null) {
            return null;
        }
        boolean failed = SettlementStatus.fromGateway(s.getStatus()) == SettlementStatus.FAILED;
        return new PayoutItem(s.getId(), s.getDriverId(), s.getAmount(), status(s), s.getSettlementDate(), s.getGatewaySettlementId(),
                s.getUpdatedAt(), failed && openRetry == null, openRetry);
    }

    static String status(DriverSettlement s) {
        SettlementStatus status = SettlementStatus.fromGateway(s.getStatus());
        return status == SettlementStatus.SUCCESS ? "PAID" : status.name();
    }

    private PayoutAccountView account(String driverId, boolean full) {
        List<PayoutAccountView.Account> accounts = payoutRecipientRepository.findByDriverIdOrderByCreatedAtDesc(driverId).stream()
                .map(r -> toAccount(r, full)).toList();
        boolean canReceive = accounts.stream().anyMatch(a -> a.isDefault() && a.linkedToPaystack());
        return new PayoutAccountView(driverId, !accounts.isEmpty(), canReceive, accounts);
    }

    private static PayoutAccountView.Account toAccount(PayoutRecipient r, boolean full) {
        return new PayoutAccountView.Account(r.getId(), r.getBankName(), r.getBankCode(), r.getAccountName(), mask(r.getAccountNumber()),
                full ? r.getAccountNumber() : null, r.isDefault(), r.getGatewaySubaccountCode() != null, r.getGatewaySubaccountCode(),
                r.getGatewaySplitPercentage(), r.getGatewaySettlementSchedule(), r.getCreatedAt());
    }

    static String mask(String number) {
        if (number == null || number.length() <= 4) {
            return number == null ? null : "****";
        }
        return "*".repeat(number.length() - 4) + number.substring(number.length() - 4);
    }

    private static String normalise(String name) {
        return name.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
