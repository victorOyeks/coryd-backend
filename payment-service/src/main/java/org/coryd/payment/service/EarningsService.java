package org.coryd.payment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.enums.PaymentMethod;
import org.coryd.common.enums.PayoutStatus;
import org.coryd.common.enums.UserRole;
import org.coryd.common.exception.BadRequestException;
import org.coryd.common.exception.UnauthorizedException;
import org.coryd.common.kafka.KafkaTopics;
import org.coryd.common.kafka.events.RefundIssuedEvent;
import org.coryd.common.kafka.events.TripCompletedEvent;
import org.coryd.common.response.PagedResponse;
import org.coryd.payment.client.ProfileServiceClient;
import org.coryd.payment.config.CommissionProperties;
import org.coryd.payment.config.KafkaConfig;
import org.coryd.payment.dto.BookingEarningsItem;
import org.coryd.payment.dto.CommissionRate;
import org.coryd.payment.dto.DriverEarningsSummary;
import org.coryd.payment.dto.DriverPayoutItem;
import org.coryd.payment.dto.EarningsPeriod;
import org.coryd.payment.dto.EarningsTripItem;
import org.coryd.payment.dto.PeriodEarningsSummary;
import org.coryd.payment.dto.SettlementStatus;
import org.coryd.payment.entity.DriverEarningsAccount;
import org.coryd.payment.entity.DriverSettlement;
import org.coryd.payment.entity.EarningsTransaction;
import org.coryd.payment.entity.FareHold;
import org.coryd.payment.entity.PaymentTransaction;
import org.coryd.payment.gateway.PaymentGatewayClient;
import org.coryd.payment.repository.DriverEarningsAccountRepository;
import org.coryd.payment.repository.DriverSettlementRepository;
import org.coryd.payment.repository.EarningsTransactionRepository;
import org.coryd.payment.repository.FareHoldRepository;
import org.coryd.payment.repository.PaymentTransactionRepository;
import org.coryd.payment.util.PeriodRange;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The driver's earnings ledger. The money itself moved at charge time - each fare was split to the
 * driver's Paystack subaccount and settles to their bank weekly - so nothing here is a balance we
 * hold. It records what each trip earned, what Paystack has paid out, and what a driver owes back.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EarningsService {

    private static final List<String> SETTLED = List.of("success");
    private static final List<String> IN_PROGRESS = List.of("pending", "processing");

    private final FareHoldRepository fareHoldRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final DriverEarningsAccountRepository earningsAccountRepository;
    private final EarningsTransactionRepository earningsTransactionRepository;
    private final DriverSettlementRepository driverSettlementRepository;
    private final PaymentGatewayClient gatewayClient;
    private final ProfileServiceClient profileServiceClient;
    private final CommissionProperties commissionProperties;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @KafkaListener(topics = KafkaTopics.TRIP_COMPLETED, groupId = KafkaConfig.GROUP_ID)
    @Transactional
    public void onTripCompleted(TripCompletedEvent event) {
        String bookingId = event.getBookingId();
        FareHold hold = fareHoldRepository.findFirstByBookingIdOrderByCreatedAtDesc(bookingId).orElse(null);
        if (hold == null || hold.isReleased() || hold.isRefunded()) {
            log.warn("No releasable fare hold for booking {}", bookingId);
            return;
        }
        if (!isPaid(bookingId)) {
            log.error("RECONCILE: trip completed for booking {} but its payment never succeeded - driver not credited", bookingId);
            return;
        }

        if (fareHoldRepository.markReleased(hold.getId()) == 0) {
            return;
        }
        creditDriver(hold.getDriverId(), bookingId, event.getRoutePostId(), event.getRiderId(), hold.getAmount(), hold.getDebtRecovery());
    }

    /**
     * Commission comes off the driver's side, same for a trip fare or a late-cancellation fee. Paystack
     * already paid the driver at charge time, so this only keeps the books: lifetime totals, the
     * statement row, and the debt this fare paid down.
     */
    private void creditDriver(String driverId, String bookingId, String routePostId, String riderId,
                              BigDecimal gross, BigDecimal debtRecovery) {
        BigDecimal commission = gross.multiply(commissionProperties.getPercentage()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal net = gross.subtract(commission);

        DriverEarningsAccount account = earningsAccountRepository.findForUpdate(driverId)
                .orElseGet(() -> DriverEarningsAccount.builder().driverId(driverId).build());
        if (debtRecovery != null && debtRecovery.signum() > 0) {
            BigDecimal recovered = debtRecovery.min(account.getSettlementDebt());
            account.setSettlementDebt(account.getSettlementDebt().subtract(recovered));
            BigDecimal excess = debtRecovery.subtract(recovered);
            if (excess.signum() > 0) {
                log.error("RECONCILE: booking {} took {} more than driver {} still owed - owed back to the driver",
                        bookingId, excess, driverId);
            }
        }
        account.setLifetimeGross(account.getLifetimeGross().add(gross));
        account.setLifetimeCommission(account.getLifetimeCommission().add(commission));
        earningsAccountRepository.save(account);

        earningsTransactionRepository.save(EarningsTransaction.builder()
                .driverId(driverId)
                .bookingId(bookingId)
                .routePostId(routePostId)
                .riderId(riderId)
                .grossFare(gross)
                .commissionAmount(commission)
                .netCredit(net)
                .build());
    }

    /** So the client computes gross/commission/net from the configured rate instead of hardcoding it. */
    public CommissionRate commissionRate() {
        return new CommissionRate(commissionProperties.getPercentage().multiply(BigDecimal.valueOf(100)));
    }

    /** Earnings screen's header: what the driver has earned, what Paystack has paid to their bank, and anything owed back. */
    @Transactional(readOnly = true)
    public DriverEarningsSummary getSummary(String driverId, String callerId, UserRole callerRole) {
        requireSelfOrOps(driverId, callerId, callerRole);
        DriverEarningsAccount account = earningsAccountRepository.findByDriverId(driverId)
                .orElseGet(() -> DriverEarningsAccount.builder().driverId(driverId).build());
        BigDecimal lifetimeNet = account.getLifetimeGross().subtract(account.getLifetimeCommission());
        BigDecimal todayNet = earningsTransactionRepository.sumNetCreditByDriverIdSince(driverId, startOfToday());
        Instant lastPayoutAt = driverSettlementRepository.lastSettledAt(driverId);
        BigDecimal sinceLastPayout = lastPayoutAt == null
                ? earningsTransactionRepository.sumNetCreditByDriverId(driverId)
                : earningsTransactionRepository.sumNetCreditByDriverIdSince(driverId, lastPayoutAt);
        return new DriverEarningsSummary(account.getLifetimeGross(), lifetimeNet, todayNet, sinceLastPayout,
                driverSettlementRepository.sumByDriverIdAndStatusIn(driverId, SETTLED),
                driverSettlementRepository.sumByDriverIdAndStatusIn(driverId, IN_PROGRESS),
                lastPayoutAt, account.getSettlementDebt());
    }

    /** Earnings screen's period-filtered Gross/Commission/Net card. */
    @Transactional(readOnly = true)
    public PeriodEarningsSummary getPeriodSummary(String driverId, EarningsPeriod period, YearMonth month,
                                                  String callerId, UserRole callerRole) {
        requireSelfOrOps(driverId, callerId, callerRole);
        PeriodRange.Range range = PeriodRange.of(period, month);
        EarningsTransactionRepository.PeriodSums sums = earningsTransactionRepository
                .sumByDriverIdAndPeriod(driverId, range.since(), range.until());
        return new PeriodEarningsSummary(sums.getGrossFare(), sums.getCommissionAmount(), sums.getNetCredit());
    }

    /** Earnings screen's trip list. Rider names and photos come from one batched profile-service call per page. */
    @Transactional(readOnly = true)
    public PagedResponse<EarningsTripItem> getTrips(String driverId, EarningsPeriod period, YearMonth month, Pageable pageable,
                                                    String callerId, UserRole callerRole) {
        requireSelfOrOps(driverId, callerId, callerRole);
        PeriodRange.Range range = PeriodRange.of(period, month);
        Page<EarningsTransaction> page = earningsTransactionRepository
                .findByDriverIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtDescIdDesc(
                        driverId, range.since(), range.until(), pageable);
        Map<String, ProfileServiceClient.RiderSummaryItem> riders = profileServiceClient.fetchRiderSummaries(
                page.getContent().stream().map(EarningsTransaction::getRiderId).filter(Objects::nonNull).distinct().toList());
        return PagedResponse.from(page.map(t -> toTripItem(t, riders)));
    }

    /** Paystack's settlements to the driver's bank, newest first. */
    @Transactional(readOnly = true)
    public PagedResponse<DriverPayoutItem> getPayouts(String driverId, Pageable pageable, String callerId, UserRole callerRole) {
        requireSelfOrOps(driverId, callerId, callerRole);
        Page<DriverSettlement> page = driverSettlementRepository.findByDriverIdOrderBySettlementDateDescCreatedAtDesc(driverId, pageable);
        return PagedResponse.from(page.map(s -> new DriverPayoutItem(s.getId(), s.getAmount(),
                SettlementStatus.fromGateway(s.getStatus()), s.getSettlementDate())));
    }

    /** Earnings breakdown for a set of bookings - e.g. the driver's trip history zips netCredit in by bookingId. */
    public List<BookingEarningsItem> getTransactionsForBookings(List<String> bookingIds) {
        return earningsTransactionRepository.findByBookingIdIn(bookingIds).stream()
                .map(t -> new BookingEarningsItem(t.getBookingId(), t.getGrossFare(), t.getCommissionAmount(), t.getNetCredit()))
                .toList();
    }

    private static EarningsTripItem toTripItem(EarningsTransaction t, Map<String, ProfileServiceClient.RiderSummaryItem> riders) {
        EarningsTripItem.Rider rider = null;
        if (t.getRiderId() != null) {
            ProfileServiceClient.RiderSummaryItem profile = riders.get(t.getRiderId());
            rider = new EarningsTripItem.Rider(t.getRiderId(), profile == null ? null : profile.firstName(),
                    profile == null ? null : profile.lastName(), profile == null ? null : profile.photoUrl());
        }
        return new EarningsTripItem(t.getId(), t.getBookingId(), t.getRoutePostId(), t.getCreatedAt(),
                t.getGrossFare(), t.getCommissionAmount(), t.getNetCredit(), rider);
    }

    private static void requireSelfOrOps(String driverId, String callerId, UserRole callerRole) {
        if (!driverId.equals(callerId) && callerRole != UserRole.OPS_ADMIN) {
            throw new UnauthorizedException("You can only view your own earnings");
        }
    }

    private static Instant startOfToday() {
        return LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /**
     * Back to the card it came from. A refusal throws, so the hold isn't marked refunded and the
     * cancellation event is retried rather than the rider silently going unpaid.
     */
    private void refundToCard(PaymentTransaction transaction, String bookingId, BigDecimal amount, String reason) {
        if (transaction.getPaymentMethod() != PaymentMethod.PAYSTACK) {
            log.error("RECONCILE: booking {} was paid by {} - there is no card to refund {} to", bookingId,
                    transaction.getPaymentMethod(), amount);
            return;
        }
        if (!gatewayClient.refund(transaction.getGatewayReference(), amount, reason)) {
            throw new IllegalStateException("Paystack refused the refund for booking " + bookingId + " - rider "
                    + transaction.getRiderId() + " is owed " + amount);
        }
    }

    /**
     * Ops records what a driver owes after Paystack takes a refund from our balance because the
     * driver's share had already settled. Not inferred here: the refund response doesn't say whether
     * the driver had been settled, and guessing would mean charging drivers twice.
     */
    @Transactional
    public void recordSettlementDebt(String driverId, BigDecimal amount, String reason, UserRole callerRole) {
        if (callerRole != UserRole.OPS_ADMIN) {
            throw new UnauthorizedException("Only an ops admin can record a settlement debt");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new BadRequestException("Debt amount must be greater than zero");
        }
        DriverEarningsAccount account = earningsAccountRepository.findForUpdate(driverId)
                .orElseGet(() -> DriverEarningsAccount.builder().driverId(driverId).build());
        account.setSettlementDebt(account.getSettlementDebt().add(amount));
        earningsAccountRepository.save(account);
        log.info("Recorded settlement debt of {} against driver {}: {}", amount, driverId, reason);
    }

    private boolean isPaid(String bookingId) {
        return paymentTransactionRepository.findFirstByBookingIdOrderByCreatedAtDesc(bookingId)
                .map(t -> t.getStatus() == PayoutStatus.PAID)
                .orElse(false);
    }

    @Transactional
    public void refund(String bookingId, String reason) {
        refund(bookingId, reason, null, null);
    }

    /** A late rider cancellation keeps fee out of the refund and records it as the driver's earning -
     * the hold is still settled exactly once, split between the two. */
    @Transactional
    public void refund(String bookingId, String reason, BigDecimal fee, String routePostId) {
        FareHold hold = fareHoldRepository.findFirstByBookingIdOrderByCreatedAtDesc(bookingId).orElse(null);
        if (hold == null || hold.isReleased() || hold.isRefunded()) {
            return;
        }

        paymentTransactionRepository.findFirstByBookingIdOrderByCreatedAtDesc(bookingId).ifPresent(transaction -> {
            // Only marked refunded once money actually goes back - a cancelled booking whose charge
            // lands later is refunded then, not skipped because this ran first.
            if (transaction.getStatus() != PayoutStatus.PAID) {
                return;
            }
            if (fareHoldRepository.markRefunded(hold.getId()) == 0) {
                return;
            }
            BigDecimal kept = fee == null ? BigDecimal.ZERO : fee.max(BigDecimal.ZERO).min(hold.getAmount());
            BigDecimal refunded = hold.getAmount().subtract(kept);
            if (kept.signum() > 0) {
                creditDriver(hold.getDriverId(), bookingId, routePostId, transaction.getRiderId(), kept, BigDecimal.ZERO);
            }
            if (refunded.signum() == 0) {
                return;
            }
            refundToCard(transaction, bookingId, refunded, reason);

            kafkaTemplate.send(KafkaTopics.REFUND_ISSUED, RefundIssuedEvent.builder()
                    .bookingId(bookingId)
                    .riderId(transaction.getRiderId())
                    .amount(refunded)
                    .reason(reason)
                    .refundedAt(LocalDateTime.now())
                    .build());
        });
    }
}
