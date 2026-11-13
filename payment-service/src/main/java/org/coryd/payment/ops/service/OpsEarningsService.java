package org.coryd.payment.ops.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.enums.UserRole;
import org.coryd.common.exception.BadRequestException;
import org.coryd.common.response.PagedResponse;
import org.coryd.payment.dto.DebtEntryItem;
import org.coryd.payment.dto.DebtEntryType;
import org.coryd.payment.dto.DriverEarningsSummary;
import org.coryd.payment.dto.OwedBalanceResponse;
import org.coryd.payment.entity.DriverDebtEntry;
import org.coryd.payment.entity.DriverEarningsAccount;
import org.coryd.payment.ops.dto.DebtAdjustmentRequest;
import org.coryd.payment.ops.dto.DriverStatementView;
import org.coryd.payment.ops.dto.OpsMoneyRequestView;
import org.coryd.payment.ops.entity.OpsMoneyAudit;
import org.coryd.payment.ops.entity.OpsMoneyRequest;
import org.coryd.payment.ops.entity.OpsRequestStatus;
import org.coryd.payment.ops.entity.OpsRequestType;
import org.coryd.payment.ops.repository.OpsMoneyQueryRepository;
import org.coryd.payment.ops.repository.OpsMoneyRequestRepository;
import org.coryd.payment.repository.DriverDebtEntryRepository;
import org.coryd.payment.repository.DriverEarningsAccountRepository;
import org.coryd.payment.repository.DriverSettlementRepository;
import org.coryd.payment.repository.EarningsTransactionRepository;
import org.coryd.payment.service.EarningsService;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * A driver's earnings statement, and ops corrections to what they owe. A correction is a row in the same debt ledger every
 * other change goes into (driver_debt_entries, type OPS_ADJUSTMENT), so the driver's own history in the app still adds up
 * to their balance; above the threshold it waits for a second admin.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpsEarningsService implements OpsMoneyExecutor {

    private final EarningsService earningsService;
    private final EarningsTransactionRepository earningsTransactionRepository;
    private final DriverSettlementRepository settlementRepository;
    private final DriverEarningsAccountRepository earningsAccountRepository;
    private final DriverDebtEntryRepository debtEntryRepository;
    private final OpsMoneyRequestRepository requestRepository;
    private final OpsMoneyQueryRepository queryRepository;
    private final OpsRequestSupport support;
    private final OpsMoneyAuditService audit;

    @Override
    public OpsRequestType type() {
        return OpsRequestType.DEBT_ADJUSTMENT;
    }

    @Transactional(readOnly = true)
    public DriverStatementView statement(String driverId, LocalDate from, LocalDate to, Instant since, Instant until, OpsActor actor) {
        DriverEarningsSummary summary = earningsService.getSummary(driverId, actor.id(), UserRole.OPS_ADMIN);
        OwedBalanceResponse owed = earningsService.owedBalance(driverId, actor.id(), UserRole.OPS_ADMIN);
        DriverEarningsAccount account = earningsAccountRepository.findByDriverId(driverId).orElse(null);
        BigDecimal lifetimeGross = account == null ? BigDecimal.ZERO : account.getLifetimeGross();
        BigDecimal lifetimeCommission = account == null ? BigDecimal.ZERO : account.getLifetimeCommission();
        EarningsTransactionRepository.PeriodSums period = earningsTransactionRepository.sumByDriverIdAndPeriod(driverId, since, until);
        BigDecimal refundsCharged = queryRepository.sum("""
                select count(*), coalesce(sum(amount), 0) from payment.driver_debt_entries
                where driver_id = :driverId and type = 'REFUNDED_TRIP' and created_at >= :from and created_at < :to
                """, since, until, Map.of("driverId", driverId)).amount();
        BigDecimal adjustments = queryRepository.sum("""
                select count(*), coalesce(sum(amount), 0) from payment.driver_debt_entries
                where driver_id = :driverId and type = 'OPS_ADJUSTMENT' and created_at >= :from and created_at < :to
                """, since, until, Map.of("driverId", driverId)).amount();
        BigDecimal failed = settlementRepository.sumByDriverIdAndStatusIn(driverId, List.of("failed"));
        return new DriverStatementView(driverId,
                new DriverStatementView.Totals(lifetimeGross, lifetimeCommission, lifetimeGross.subtract(lifetimeCommission)),
                new DriverStatementView.Period(from, to, period.getGrossFare(), period.getCommissionAmount(), period.getNetCredit(),
                        refundsCharged, adjustments),
                new DriverStatementView.Owed(owed.amountOwed(), owed.owedLimit(), owed.blocked(), owed.minimumToUnblock()),
                new DriverStatementView.Payouts(summary.paidToBank(), summary.payoutInProgress(), failed, summary.lastPayoutAt()),
                earningsService.commissionRate().percentage());
    }

    @Transactional(readOnly = true)
    public PagedResponse<DebtEntryItem> debtEntries(String driverId, Pageable pageable, OpsActor actor) {
        return earningsService.owedHistory(driverId, pageable, actor.id(), UserRole.OPS_ADMIN);
    }

    @Transactional
    public OpsMoneyRequestView adjust(String driverId, DebtAdjustmentRequest body, OpsActor actor) {
        String reason = OpsRequestSupport.requireReason(body.reason());
        if (body.direction() == null) {
            throw new BadRequestException("direction is required (DEBIT or CREDIT)");
        }
        BigDecimal amount = OpsRequestSupport.amount(body.amount());
        DriverEarningsAccount account = earningsAccountRepository.findForUpdate(driverId).orElse(null);
        var replay = support.replay(body.idempotencyKey(), OpsRequestType.DEBT_ADJUSTMENT, driverId);
        if (replay.isPresent()) {
            return support.view(replay.get(), actor);
        }
        BigDecimal owed = account == null ? BigDecimal.ZERO : account.getSettlementDebt();
        if (body.direction() == OpsMoneyRequest.Direction.CREDIT && amount.compareTo(owed) > 0) {
            throw new BadRequestException("This driver only owes " + OpsRequestSupport.naira(owed) + " - a credit can't take it below zero");
        }
        OpsMoneyRequest request = support.create(OpsMoneyRequest.builder()
                .type(OpsRequestType.DEBT_ADJUSTMENT)
                .direction(body.direction())
                .driverId(driverId)
                .targetId(driverId), amount, reason, body.idempotencyKey(), actor);
        audit.record(actor, OpsMoneyAudit.Action.DEBT_ADJUSTMENT_REQUESTED, OpsMoneyAudit.TargetType.DRIVER, driverId, request.getId(),
                amount, Map.of("owed", owed), Map.of("status", request.getStatus(), "direction", body.direction()), reason);
        if (request.getStatus() == OpsRequestStatus.PENDING_APPROVAL) {
            return support.view(request, actor);
        }
        return support.view(execute(request, actor), actor);
    }

    /** One OPS_ADJUSTMENT entry, written under the driver's account lock - the same lock every refund and fare takes. */
    @Override
    public OpsMoneyRequest execute(OpsMoneyRequest request, OpsActor actor) {
        String driverId = request.getDriverId();
        DriverEarningsAccount account = earningsAccountRepository.findForUpdate(driverId)
                .orElseGet(() -> DriverEarningsAccount.builder().driverId(driverId).build());
        BigDecimal before = account.getSettlementDebt();
        BigDecimal change = request.getDirection() == OpsMoneyRequest.Direction.CREDIT ? request.getAmount().negate() : request.getAmount();
        if (before.add(change).signum() < 0) {
            throw new BadRequestException("This driver only owes " + OpsRequestSupport.naira(before) + " now - a credit can't take it below zero");
        }
        account.setSettlementDebt(before.add(change));
        earningsAccountRepository.save(account);
        DriverDebtEntry entry = debtEntryRepository.save(DriverDebtEntry.builder()
                .driverId(driverId)
                .type(DebtEntryType.OPS_ADJUSTMENT)
                .amount(change)
                .balanceAfter(account.getSettlementDebt())
                .reference("ops:" + request.getId())
                .note(OpsMoneyAuditService.truncate(request.getReason(), 255))
                .build());
        request.setAttempts(request.getAttempts() + 1);
        request.setStatus(OpsRequestStatus.COMPLETED);
        request.setExecutedAt(Instant.now());
        OpsMoneyRequest saved = requestRepository.save(request);
        audit.record(actor, OpsMoneyAudit.Action.DEBT_ADJUSTED, OpsMoneyAudit.TargetType.DRIVER, driverId, saved.getId(), change,
                Map.of("owed", before), Map.of("owed", account.getSettlementDebt(), "entryId", entry.getId()), saved.getReason());
        log.info("OPS debt adjustment {} on driver {}: {} -> {}", saved.getId(), driverId, before, account.getSettlementDebt());
        return saved;
    }
}
