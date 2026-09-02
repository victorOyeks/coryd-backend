package org.coryd.payment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.enums.PaymentMethod;
import org.coryd.common.enums.PayoutStatus;
import org.coryd.common.kafka.KafkaTopics;
import org.coryd.common.kafka.events.RefundIssuedEvent;
import org.coryd.common.kafka.events.TripCompletedEvent;
import org.coryd.payment.config.CommissionProperties;
import org.coryd.payment.config.KafkaConfig;
import org.coryd.payment.dto.BookingEarningsItem;
import org.coryd.payment.dto.DriverEarningsSummary;
import org.coryd.payment.dto.EarningsPeriod;
import org.coryd.payment.dto.PeriodEarningsSummary;
import org.coryd.payment.entity.DriverEarningsAccount;
import org.coryd.payment.entity.EarningsTransaction;
import org.coryd.payment.entity.FareHold;
import org.coryd.payment.gateway.PaystackGatewayClient;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class EarningsService {

    private final FareHoldRepository fareHoldRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final DriverEarningsAccountRepository earningsAccountRepository;
    private final EarningsTransactionRepository earningsTransactionRepository;
    private final WalletFeedRepository walletFeedRepository;
    private final CommissionProperties commissionProperties;
    private final WalletService walletService;
    private final PaystackGatewayClient paystackGatewayClient;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @KafkaListener(topics = KafkaTopics.TRIP_COMPLETED, groupId = KafkaConfig.GROUP_ID)
    @Transactional
    public void onTripCompleted(TripCompletedEvent event) {
        String bookingId = event.getBookingId();
        FareHold hold = fareHoldRepository.findByBookingId(bookingId).orElse(null);
        if (hold == null || hold.isReleased() || hold.isRefunded()) {
            log.warn("No releasable fare hold for booking {}", bookingId);
            return;
        }

        BigDecimal commission = hold.getAmount().multiply(commissionProperties.getPercentage());
        BigDecimal net = hold.getAmount().subtract(commission);

        hold.setReleased(true);
        fareHoldRepository.save(hold);

        DriverEarningsAccount account = earningsAccountRepository.findByDriverId(hold.getDriverId())
                .orElseGet(() -> DriverEarningsAccount.builder().driverId(hold.getDriverId()).build());
        account.setAvailableBalance(account.getAvailableBalance().add(net));
        account.setLifetimeGross(account.getLifetimeGross().add(hold.getAmount()));
        account.setLifetimeCommission(account.getLifetimeCommission().add(commission));
        earningsAccountRepository.save(account);

        earningsTransactionRepository.save(EarningsTransaction.builder()
                .driverId(hold.getDriverId())
                .bookingId(bookingId)
                .routePostId(event.getRoutePostId())
                .grossFare(hold.getAmount())
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

    /** Wallet screen's mixed Trip/Withdrawal transaction feed - type=null means "All". */
    public Page<WalletTransactionItem> getTransactionFeed(String driverId, WalletTransactionType type,
                                                            EarningsPeriod period, YearMonth month, Pageable pageable) {
        PeriodRange.Range range = PeriodRange.of(period, month);
        return walletFeedRepository.findFeed(driverId, type, range.since(), range.until(), pageable);
    }

    /** Dashboard's two earnings tiles - lifetimeNet isn't a stored column, it's derived here rather than on every read of the raw account row. */
    public DriverEarningsSummary getSummary(String driverId) {
        DriverEarningsAccount account = earningsAccountRepository.findByDriverId(driverId)
                .orElseGet(() -> DriverEarningsAccount.builder().driverId(driverId).build());
        BigDecimal lifetimeNet = account.getLifetimeGross().subtract(account.getLifetimeCommission());
        BigDecimal todayNet = earningsTransactionRepository.sumNetCreditByDriverIdSince(driverId, startOfToday());
        return new DriverEarningsSummary(account.getLifetimeGross(), lifetimeNet, todayNet, account.getAvailableBalance());
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

    @Transactional
    public void refund(String bookingId, String reason) {
        FareHold hold = fareHoldRepository.findByBookingId(bookingId).orElse(null);
        if (hold == null || hold.isReleased() || hold.isRefunded()) {
            return;
        }
        hold.setRefunded(true);
        fareHoldRepository.save(hold);

        paymentTransactionRepository.findByBookingId(bookingId).ifPresent(transaction -> {
            // We fully own the wallet ledger, so a wallet-paid booking gets its money back
            // immediately. A Paystack-paid booking that never actually succeeded (still PENDING,
            // or FAILED) has nothing to refund - only PAID has real money on Paystack's side to
            // reverse; refunding anything else would just fail the gateway call outright.
            if (transaction.getPaymentMethod() == PaymentMethod.WALLET) {
                walletService.creditRefund(transaction.getRiderId(), bookingId, hold.getAmount(), reason);
            } else if (transaction.getPaymentMethod() == PaymentMethod.PAYSTACK
                    && transaction.getStatus() == PayoutStatus.PAID) {
                paystackGatewayClient.refundTransaction(transaction.getGatewayReference(), hold.getAmount(), reason);
            }

            kafkaTemplate.send(KafkaTopics.REFUND_ISSUED, RefundIssuedEvent.builder()
                    .bookingId(bookingId)
                    .riderId(transaction.getRiderId())
                    .amount(hold.getAmount())
                    .reason(reason)
                    .refundedAt(LocalDateTime.now())
                    .build());
        });
    }
}
