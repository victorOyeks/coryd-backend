package org.coryd.payment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.exception.BadRequestException;
import org.coryd.common.enums.PaymentMethod;
import org.coryd.common.enums.PayoutStatus;
import org.coryd.common.kafka.KafkaTopics;
import org.coryd.common.kafka.events.RefundIssuedEvent;
import org.coryd.common.kafka.events.TripCompletedEvent;
import org.coryd.payment.client.ProfileServiceClient;
import org.coryd.payment.config.CommissionProperties;
import org.coryd.payment.config.KafkaConfig;
import org.coryd.payment.dto.BookingEarningsItem;
import org.coryd.payment.dto.DriverEarningsSummary;
import org.coryd.payment.dto.EarningsPeriod;
import org.coryd.payment.dto.PeriodEarningsSummary;
import org.coryd.payment.gateway.PaymentGatewayClient;
import org.coryd.payment.entity.PaymentTransaction;
import org.coryd.payment.entity.DriverEarningsAccount;
import org.coryd.payment.entity.EarningsTransaction;
import org.coryd.payment.entity.FareHold;
import org.coryd.payment.dto.WalletTransactionItem;
import org.coryd.payment.dto.WalletTransactionType;
import org.coryd.payment.repository.DriverEarningsAccountRepository;
import org.coryd.payment.repository.EarningsTransactionRepository;
import org.coryd.payment.repository.FareHoldRepository;
import org.coryd.payment.repository.PaymentTransactionRepository;
import org.coryd.payment.repository.WalletFeedRepository;
import org.coryd.payment.util.PeriodRange;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.beans.factory.annotation.Value;
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

@Slf4j
@Service
@RequiredArgsConstructor
public class EarningsService {

    private final FareHoldRepository fareHoldRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final DriverEarningsAccountRepository earningsAccountRepository;
    private final PaymentGatewayClient gatewayClient;

    /** When true the driver's share never reaches our balance, so there is nothing here to hold. */
    @Value("${payment.split.enabled:false}")
    private boolean splitEnabled;
    private final EarningsTransactionRepository earningsTransactionRepository;
    private final WalletFeedRepository walletFeedRepository;
    private final ProfileServiceClient profileServiceClient;
    private final CommissionProperties commissionProperties;
    private final WalletService walletService;
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
        creditDriver(hold.getDriverId(), bookingId, event.getRoutePostId(), event.getRiderId(), hold.getAmount());
    }

    /**
     * Commission comes off the driver's side, same for a trip fare or a late-cancellation fee.
     * Under split payments the money moved at charge time, so this only keeps the books: lifetime
     * totals, the statement row, and whatever debt the trip paid down.
     */
    private void creditDriver(String driverId, String bookingId, String routePostId, String riderId, BigDecimal gross) {
        BigDecimal commission = gross.multiply(commissionProperties.getPercentage()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal net = gross.subtract(commission);

        DriverEarningsAccount account = earningsAccountRepository.findForUpdate(driverId)
                .orElseGet(() -> DriverEarningsAccount.builder().driverId(driverId).build());
        if (settledByGateway(bookingId)) {
            // Paystack already paid the driver at charge time, so there is nothing to hold here -
            // only a debt this trip may have paid down.
            BigDecimal recovered = account.getSettlementDebt().min(net);
            if (recovered.signum() > 0) {
                account.setSettlementDebt(account.getSettlementDebt().subtract(recovered));
            }
        } else {
            // A wallet-funded fare never reached the gateway, so it was never split - we are holding
            // the driver's share and still owe it to them.
            account.setAvailableBalance(account.getAvailableBalance().add(net));
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

    /** Wallet screen's period-filtered Gross/Commission/Net card. */
    public PeriodEarningsSummary getPeriodSummary(String driverId, EarningsPeriod period, YearMonth month) {
        PeriodRange.Range range = PeriodRange.of(period, month);
        EarningsTransactionRepository.PeriodSums sums = earningsTransactionRepository
                .sumByDriverIdAndPeriod(driverId, range.since(), range.until());
        return new PeriodEarningsSummary(sums.getGrossFare(), sums.getCommissionAmount(), sums.getNetCredit());
    }

    /** Wallet screen's mixed Trip/Withdrawal transaction feed - type=null means "All". Withdrawal
     * rows come out of the query already carrying their bank account; trip rows carry only the
     * rider's id, so the names and photos are fetched here in one batched call for the whole page
     * rather than one call per row. */
    public Page<WalletTransactionItem> getTransactionFeed(String driverId, WalletTransactionType type,
                                                            EarningsPeriod period, YearMonth month, Pageable pageable) {
        PeriodRange.Range range = PeriodRange.of(period, month);
        Page<WalletTransactionItem> feed = walletFeedRepository.findFeed(driverId, type, range.since(), range.until(), pageable);
        Map<String, ProfileServiceClient.RiderSummaryItem> profiles = riderProfiles(feed.getContent());
        return feed.map(item -> withRiderProfile(item, profiles));
    }

    private Map<String, ProfileServiceClient.RiderSummaryItem> riderProfiles(List<WalletTransactionItem> items) {
        List<String> riderIds = items.stream()
                .map(WalletTransactionItem::rider)
                .filter(Objects::nonNull)
                .map(WalletTransactionItem.Rider::riderId)
                .distinct()
                .toList();
        return profileServiceClient.fetchRiderSummaries(riderIds);
    }

    /** A rider with no profile row, or a profile-service call that failed, leaves the id in place
     * with blank display fields - the trip row still renders, just without a name. */
    private static WalletTransactionItem withRiderProfile(WalletTransactionItem item,
                                                            Map<String, ProfileServiceClient.RiderSummaryItem> profiles) {
        if (item.rider() == null) {
            return item;
        }
        ProfileServiceClient.RiderSummaryItem profile = profiles.get(item.rider().riderId());
        if (profile == null) {
            return item;
        }
        return item.withRider(new WalletTransactionItem.Rider(
                item.rider().riderId(), profile.firstName(), profile.lastName(), profile.photoUrl()));
    }

    /** Dashboard's two earnings tiles - lifetimeNet isn't a stored column, it's derived here rather than on every read of the raw account row. */
    public DriverEarningsSummary getSummary(String driverId) {
        DriverEarningsAccount account = earningsAccountRepository.findByDriverId(driverId)
                .orElseGet(() -> DriverEarningsAccount.builder().driverId(driverId).build());
        BigDecimal lifetimeNet = account.getLifetimeGross().subtract(account.getLifetimeCommission());
        BigDecimal todayNet = earningsTransactionRepository.sumNetCreditByDriverIdSince(driverId, startOfToday());
        return new DriverEarningsSummary(account.getLifetimeGross(), lifetimeNet, todayNet,
                account.getAvailableBalance(), account.getSettlementDebt());
    }

    /** Earnings breakdown lookup for a specific set of bookings - e.g. trip-service's driver history page zips netCredit in per route by bookingId, and the post-trip settlement summary sums all three fields across a route's completed bookings. */
    public List<BookingEarningsItem> getTransactionsForBookings(List<String> bookingIds) {
        return earningsTransactionRepository.findByBookingIdIn(bookingIds).stream()
                .map(t -> new BookingEarningsItem(t.getBookingId(), t.getGrossFare(), t.getCommissionAmount(), t.getNetCredit()))
                .toList();
    }

    private static Instant startOfToday() {
        return LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /**
     * A card charge goes back to the card, because under split payments the driver's share is at
     * Paystack and only their refund can reach it. A wallet-funded booking never touched the
     * gateway, so it goes back to the wallet it came from.
     */
    private void refundToSource(PaymentTransaction transaction, String bookingId, BigDecimal amount, String reason) {
        if (!splitEnabled || transaction.getPaymentMethod() != PaymentMethod.PAYSTACK) {
            walletService.creditRefund(transaction.getRiderId(), bookingId, amount, reason);
            return;
        }
        if (!gatewayClient.refund(transaction.getGatewayReference(), amount, reason)) {
            log.error("RECONCILE: Paystack refused the refund for booking {} - rider {} is owed {}",
                    bookingId, transaction.getRiderId(), amount);
        }
    }

    /**
     * Ops records what a driver owes after Paystack reports a refund it could no longer take out of
     * an unsettled split. It is not inferred here: the refund response does not say whether the
     * driver had already been settled, and guessing would mean charging drivers twice.
     */
    @Transactional
    public void recordSettlementDebt(String driverId, BigDecimal amount, String reason) {
        if (amount == null || amount.signum() <= 0) {
            throw new BadRequestException("Debt amount must be greater than zero");
        }
        DriverEarningsAccount account = earningsAccountRepository.findForUpdate(driverId)
                .orElseGet(() -> DriverEarningsAccount.builder().driverId(driverId).build());
        account.setSettlementDebt(account.getSettlementDebt().add(amount));
        earningsAccountRepository.save(account);
        log.info("Recorded settlement debt of {} against driver {}: {}", amount, driverId, reason);
    }

    /** Only a card charge can be split, so only a card charge settles itself. */
    private boolean settledByGateway(String bookingId) {
        return splitEnabled && paymentTransactionRepository.findFirstByBookingIdOrderByCreatedAtDesc(bookingId)
                .map(t -> t.getPaymentMethod() == PaymentMethod.PAYSTACK)
                .orElse(false);
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

    /** A late rider cancellation keeps fee out of the refund and pays it to the driver instead - the
     * hold is still settled exactly once, split between the two. */
    @Transactional
    public void refund(String bookingId, String reason, BigDecimal fee, String routePostId) {
        FareHold hold = fareHoldRepository.findFirstByBookingIdOrderByCreatedAtDesc(bookingId).orElse(null);
        if (hold == null || hold.isReleased() || hold.isRefunded()) {
            return;
        }

        paymentTransactionRepository.findFirstByBookingIdOrderByCreatedAtDesc(bookingId).ifPresent(transaction -> {
            // Only marked refunded once money actually goes back - a cancelled booking whose Paystack
            // charge lands later is refunded then, not skipped because this ran first.
            if (transaction.getStatus() != PayoutStatus.PAID) {
                return;
            }
            if (fareHoldRepository.markRefunded(hold.getId()) == 0) {
                return;
            }
            BigDecimal kept = fee == null ? BigDecimal.ZERO : fee.max(BigDecimal.ZERO).min(hold.getAmount());
            BigDecimal refunded = hold.getAmount().subtract(kept);
            if (kept.signum() > 0) {
                creditDriver(hold.getDriverId(), bookingId, routePostId, transaction.getRiderId(), kept);
            }
            if (refunded.signum() == 0) {
                return;
            }
            refundToSource(transaction, bookingId, refunded, reason);

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
