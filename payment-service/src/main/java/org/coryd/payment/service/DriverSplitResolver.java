package org.coryd.payment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.exception.BadRequestException;
import org.coryd.payment.config.CommissionProperties;
import org.coryd.payment.entity.DriverEarningsAccount;
import org.coryd.payment.entity.PayoutRecipient;
import org.coryd.payment.repository.DriverEarningsAccountRepository;
import org.coryd.payment.repository.FareHoldRepository;
import org.coryd.payment.repository.PayoutRecipientRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Works out how a fare is divided at charge time. The driver's share goes straight to their own
 * Paystack subaccount and never reaches our balance, which is the whole point - we are not
 * licensed to hold it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DriverSplitResolver {

    private final PayoutRecipientRepository payoutRecipientRepository;
    private final DriverEarningsAccountRepository earningsAccountRepository;
    private final FareHoldRepository fareHoldRepository;
    private final CommissionProperties commissionProperties;

    public Split resolve(String driverId, BigDecimal fare) {
        PayoutRecipient recipient = payoutRecipientRepository
                .findFirstByDriverIdAndIsDefaultTrueAndGatewaySubaccountCodeIsNotNull(driverId)
                .orElseThrow(() -> new BadRequestException(
                        "This driver has not finished setting up payouts, so this trip cannot be booked yet"));

        BigDecimal recovery = debtRecovery(driverId, fare);
        if (recovery.signum() == 0) {
            return new Split(recipient.getGatewaySubaccountCode(), null, BigDecimal.ZERO);
        }
        BigDecimal commission = fare.multiply(commissionProperties.getPercentage()).setScale(2, RoundingMode.HALF_UP);
        // transaction_charge replaces the subaccount's percentage for this charge, so it carries our commission too.
        long flatChargeKobo = commission.add(recovery).movePointRight(2).longValueExact();
        log.info("Driver {} owes money - taking {} of this {} fare to recover it", driverId, recovery, fare);
        return new Split(recipient.getGatewaySubaccountCode(), flatChargeKobo, recovery);
    }

    /** Only what is still owed after other paid fares' recoveries, and never more than the driver's share of this one. */
    private BigDecimal debtRecovery(String driverId, BigDecimal fare) {
        BigDecimal debt = earningsAccountRepository.findByDriverId(driverId)
                .map(DriverEarningsAccount::getSettlementDebt)
                .orElse(BigDecimal.ZERO);
        if (debt == null || debt.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal unclaimed = debt.subtract(fareHoldRepository.outstandingDebtRecovery(driverId));
        BigDecimal driverShare = fare.subtract(fare.multiply(commissionProperties.getPercentage()).setScale(2, RoundingMode.HALF_UP));
        return unclaimed.max(BigDecimal.ZERO).min(driverShare);
    }

    public record Split(String subaccountCode, Long flatChargeKobo, BigDecimal debtRecovery) {
    }
}
