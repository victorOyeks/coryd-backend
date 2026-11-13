package org.coryd.payment.ops.service;

import lombok.RequiredArgsConstructor;
import org.coryd.common.exception.BadRequestException;
import org.coryd.payment.ops.OpsMoneyProperties;
import org.coryd.payment.ops.dto.FinanceSummary;
import org.coryd.payment.ops.entity.OpsRequestStatus;
import org.coryd.payment.ops.entity.OpsRequestType;
import org.coryd.payment.ops.repository.OpsMoneyQueryRepository;
import org.coryd.payment.ops.repository.OpsMoneyQueryRepository.Sum;
import org.coryd.payment.ops.repository.OpsMoneyRequestRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Finance dashboard - sums over Lagos days, and a daily series for the charts. */
@Service
@RequiredArgsConstructor
public class OpsFinanceService {

    public static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");

    private static final String PAID_BOOKINGS = "status = 'PAID' and purpose = 'BOOKING_PAYMENT'";
    private static final String BOOKING_REFUNDS = "status = 'REFUNDED' and kind = 'CANCELLATION'";

    private final OpsMoneyQueryRepository query;
    private final OpsMoneyRequestRepository requestRepository;
    private final OpsMoneyProperties properties;

    public static Instant startOf(LocalDate day) {
        return day.atStartOfDay(LAGOS).toInstant();
    }

    @Transactional(readOnly = true)
    public FinanceSummary summary(LocalDate from, LocalDate to) {
        LocalDate today = LocalDate.now(LAGOS);
        LocalDate end = to == null ? today : to;
        LocalDate start = from == null ? end.minusDays(29) : from;
        if (start.isAfter(end)) {
            throw new BadRequestException("from must be on or before to");
        }
        if (ChronoUnit.DAYS.between(start, end) + 1 > properties.getMaxDashboardDays()) {
            throw new BadRequestException("The window can be at most " + properties.getMaxDashboardDays() + " days");
        }
        Instant since = startOf(start);
        Instant until = startOf(end.plusDays(1));

        Sum gross = query.sum("select count(*), coalesce(sum(amount), 0) from payment.payment_transactions where " + PAID_BOOKINGS
                + " and updated_at >= :from and updated_at < :to", since, until);
        Sum refunds = query.sum("select count(*), coalesce(sum(amount), 0) from payment.refunds where " + BOOKING_REFUNDS
                + " and coalesce(refunded_at, created_at) >= :from and coalesce(refunded_at, created_at) < :to", since, until);
        Sum duplicates = query.sum("select count(*), coalesce(sum(amount), 0) from payment.refunds where status = 'REFUNDED'"
                + " and kind = 'DUPLICATE_CHARGE' and coalesce(refunded_at, created_at) >= :from and coalesce(refunded_at, created_at) < :to",
                since, until);
        Sum repaid = query.sum("select count(*), coalesce(sum(amount), 0) from payment.payment_transactions where status = 'PAID'"
                + " and purpose = 'DEBT_REPAYMENT' and updated_at >= :from and updated_at < :to", since, until);
        Sum tripGross = query.sum("select count(*), coalesce(sum(gross_fare), 0) from payment.earnings_transactions"
                + " where created_at >= :from and created_at < :to", since, until);
        Sum commission = query.sum("select count(*), coalesce(sum(commission_amount), 0) from payment.earnings_transactions"
                + " where created_at >= :from and created_at < :to", since, until);
        Sum paidOut = query.sum("select count(*), coalesce(sum(amount), 0) from payment.driver_settlements where status = 'success'"
                + " and coalesce(settlement_date, created_at) >= :from and coalesce(settlement_date, created_at) < :to", since, until);
        Sum failedOut = query.sum("select count(*), coalesce(sum(amount), 0) from payment.driver_settlements where status = 'failed'"
                + " and coalesce(settlement_date, created_at) >= :from and coalesce(settlement_date, created_at) < :to", since, until);
        Sum pendingOut = query.sum("select count(*), coalesce(sum(amount), 0) from payment.driver_settlements"
                + " where status in ('pending', 'processing')", null, null);
        Sum debt = query.sum("select count(*), coalesce(sum(settlement_debt), 0) from payment.driver_earnings_accounts"
                + " where settlement_debt > 0", null, null);
        Sum failedPayments = query.sum("select count(*), coalesce(sum(amount), 0) from payment.payment_transactions"
                + " where status = 'FAILED' and updated_at >= :from and updated_at < :to", since, until);
        Sum awaitingRetry = query.sum("select count(*), coalesce(sum(refund_due), 0) from payment.fare_holds where refund_due > 0",
                null, null);
        long failedManual = requestRepository.findByTypeAndStatusOrderByCreatedAtDesc(OpsRequestType.MANUAL_REFUND, OpsRequestStatus.FAILED).size();
        long approvals = requestRepository.countByStatus(OpsRequestStatus.PENDING_APPROVAL);

        Map<LocalDate, Sum> dGross = query.daily("payment.payment_transactions", "updated_at", "amount", PAID_BOOKINGS, since, until);
        Map<LocalDate, Sum> dRefunds = query.daily("payment.refunds", "coalesce(refunded_at, created_at)", "amount", BOOKING_REFUNDS, since, until);
        Map<LocalDate, Sum> dCommission = query.daily("payment.earnings_transactions", "created_at", "commission_amount", null, since, until);
        Map<LocalDate, Sum> dPayouts = query.daily("payment.driver_settlements", "coalesce(settlement_date, created_at)", "amount",
                "status = 'success'", since, until);
        List<FinanceSummary.Day> daily = new ArrayList<>();
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            Sum g = dGross.get(d);
            daily.add(new FinanceSummary.Day(d, amount(g), g == null ? 0 : g.count(), amount(dRefunds.get(d)),
                    amount(dCommission.get(d)), amount(dPayouts.get(d))));
        }

        return new FinanceSummary(start, end, LAGOS.getId(), money(gross), money(refunds), money(duplicates),
                gross.amount().subtract(refunds.amount()),
                new FinanceSummary.Commission(tripGross.amount(), commission.amount(), tripGross.amount().subtract(commission.amount())),
                money(repaid),
                new FinanceSummary.Payouts(money(paidOut), money(failedOut), money(pendingOut)),
                new FinanceSummary.Debt(debt.amount(), debt.count()),
                new FinanceSummary.Failures(failedPayments.count(), failedOut.count(), money(awaitingRetry), failedManual, approvals),
                daily);
    }

    private static FinanceSummary.Money money(Sum sum) {
        return new FinanceSummary.Money(sum.count(), sum.amount());
    }

    private static BigDecimal amount(Sum sum) {
        return sum == null ? BigDecimal.ZERO : sum.amount();
    }
}
