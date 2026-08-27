package org.coryd.payment.dev;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.enums.WalletEntryType;
import org.coryd.payment.entity.DriverEarningsAccount;
import org.coryd.payment.entity.EarningsTransaction;
import org.coryd.payment.entity.Wallet;
import org.coryd.payment.entity.WalletTransaction;
import org.coryd.payment.repository.DriverEarningsAccountRepository;
import org.coryd.payment.repository.EarningsTransactionRepository;
import org.coryd.payment.repository.FareHoldRepository;
import org.coryd.payment.repository.PaymentTransactionRepository;
import org.coryd.payment.repository.WalletRepository;
import org.coryd.payment.repository.WalletTransactionRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Dev-only counterpart to trip-service's route reset: drops the payment rows belonging to
 * bookings that no longer exist. Deleting the ledger rows on their own would leave
 * Wallet.availableBalance and DriverEarningsAccount's running totals reporting money from
 * trips that were wiped, so each deleted entry is also reversed out of its aggregate.
 *
 * Never registered unless coryd.dev-tools.enabled is true.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "coryd.dev-tools.enabled", havingValue = "true")
public class PaymentDevService {

    private final FareHoldRepository fareHoldRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final EarningsTransactionRepository earningsTransactionRepository;
    private final WalletTransactionRepository walletTransactionRepository;
    private final WalletRepository walletRepository;
    private final DriverEarningsAccountRepository driverEarningsAccountRepository;

    @Transactional
    public PurgeBookingsResult purge(List<String> bookingIds) {
        if (bookingIds == null || bookingIds.isEmpty()) {
            return new PurgeBookingsResult(0, 0, 0, 0, 0, 0);
        }

        var fareHolds = fareHoldRepository.findByBookingIdIn(bookingIds);
        var paymentTransactions = paymentTransactionRepository.findByBookingIdIn(bookingIds);
        var earningsTransactions = earningsTransactionRepository.findByBookingIdIn(bookingIds);
        var walletTransactions = walletTransactionRepository.findByBookingIdIn(bookingIds);

        int walletsAdjusted = reverseWalletEntries(walletTransactions);
        int earningsAccountsAdjusted = reverseEarnings(earningsTransactions);

        fareHoldRepository.deleteAll(fareHolds);
        paymentTransactionRepository.deleteAll(paymentTransactions);
        earningsTransactionRepository.deleteAll(earningsTransactions);
        walletTransactionRepository.deleteAll(walletTransactions);

        PurgeBookingsResult result = new PurgeBookingsResult(
                fareHolds.size(), paymentTransactions.size(), earningsTransactions.size(),
                walletTransactions.size(), walletsAdjusted, earningsAccountsAdjusted);
        log.warn("DEV purge for {} booking(s): {}", bookingIds.size(), result);
        return result;
    }

    /** A CREDIT put money in, so undoing it takes the same amount back out, and vice versa. */
    private int reverseWalletEntries(List<WalletTransaction> transactions) {
        Map<String, BigDecimal> deltaByWalletId = new HashMap<>();
        for (WalletTransaction transaction : transactions) {
            BigDecimal signed = transaction.getEntryType() == WalletEntryType.CREDIT
                    ? transaction.getAmount().negate()
                    : transaction.getAmount();
            deltaByWalletId.merge(transaction.getWalletId(), signed, BigDecimal::add);
        }

        Set<String> adjusted = new HashSet<>();
        deltaByWalletId.forEach((walletId, delta) -> walletRepository.findById(walletId).ifPresent(wallet -> {
            wallet.setAvailableBalance(floorAtZero(wallet.getAvailableBalance().add(delta)));
            walletRepository.save(wallet);
            adjusted.add(walletId);
        }));
        return adjusted.size();
    }

    private int reverseEarnings(List<EarningsTransaction> transactions) {
        Set<String> adjusted = new HashSet<>();
        for (EarningsTransaction transaction : transactions) {
            DriverEarningsAccount account = driverEarningsAccountRepository
                    .findByDriverId(transaction.getDriverId()).orElse(null);
            if (account == null) {
                continue;
            }
            account.setAvailableBalance(floorAtZero(account.getAvailableBalance().subtract(transaction.getNetCredit())));
            account.setLifetimeGross(floorAtZero(account.getLifetimeGross().subtract(transaction.getGrossFare())));
            account.setLifetimeCommission(floorAtZero(account.getLifetimeCommission().subtract(transaction.getCommissionAmount())));
            driverEarningsAccountRepository.save(account);
            adjusted.add(transaction.getDriverId());
        }
        return adjusted.size();
    }

    /**
     * Balances can already have been drawn down by a withdrawal, in which case reversing the
     * original credit would push them negative. Dev data is not worth reconstructing exactly -
     * clamping keeps the account usable instead of stuck below zero.
     */
    private BigDecimal floorAtZero(BigDecimal value) {
        return value.signum() < 0 ? BigDecimal.ZERO : value;
    }
}
