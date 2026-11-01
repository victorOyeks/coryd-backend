package org.coryd.payment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.payment.dto.PurgeBookingsResult;
import org.coryd.payment.entity.DriverEarningsAccount;
import org.coryd.payment.entity.EarningsTransaction;
import org.coryd.payment.repository.DriverEarningsAccountRepository;
import org.coryd.payment.repository.EarningsTransactionRepository;
import org.coryd.payment.repository.FareHoldRepository;
import org.coryd.payment.repository.PaymentTransactionRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Dev-only counterpart to trip-service's route reset: drops the payment rows belonging to
 * bookings that no longer exist. Deleting the ledger rows on their own would leave
 * DriverEarningsAccount's running totals reporting money from trips that were wiped, so each
 * deleted entry is also reversed out of its account.
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
    private final DriverEarningsAccountRepository driverEarningsAccountRepository;
    private final EarningsPublisher earningsPublisher;

    @Transactional
    public PurgeBookingsResult purge(List<String> bookingIds) {
        if (bookingIds == null || bookingIds.isEmpty()) {
            return new PurgeBookingsResult(0, 0, 0, 0);
        }

        var fareHolds = fareHoldRepository.findByBookingIdIn(bookingIds);
        var paymentTransactions = paymentTransactionRepository.findByBookingIdIn(bookingIds);
        var earningsTransactions = earningsTransactionRepository.findByBookingIdIn(bookingIds);

        int earningsAccountsAdjusted = reverseEarnings(earningsTransactions);

        fareHoldRepository.deleteAll(fareHolds);
        paymentTransactionRepository.deleteAll(paymentTransactions);
        earningsTransactionRepository.deleteAll(earningsTransactions);

        PurgeBookingsResult result = new PurgeBookingsResult(
                fareHolds.size(), paymentTransactions.size(), earningsTransactions.size(), earningsAccountsAdjusted);
        log.warn("DEV purge for {} booking(s): {}", bookingIds.size(), result);
        return result;
    }

    private int reverseEarnings(List<EarningsTransaction> transactions) {
        Set<String> adjusted = new HashSet<>();
        for (EarningsTransaction transaction : transactions) {
            DriverEarningsAccount account = driverEarningsAccountRepository
                    .findByDriverId(transaction.getDriverId()).orElse(null);
            if (account == null) {
                continue;
            }
            account.setLifetimeGross(floorAtZero(account.getLifetimeGross().subtract(transaction.getGrossFare())));
            account.setLifetimeCommission(floorAtZero(account.getLifetimeCommission().subtract(transaction.getCommissionAmount())));
            driverEarningsAccountRepository.save(account);
            earningsPublisher.changed(transaction.getDriverId());
            adjusted.add(transaction.getDriverId());
        }
        return adjusted.size();
    }

    /** Dev data is not worth reconstructing exactly - clamping keeps the account usable instead of stuck below zero. */
    private BigDecimal floorAtZero(BigDecimal value) {
        return value.signum() < 0 ? BigDecimal.ZERO : value;
    }
}
