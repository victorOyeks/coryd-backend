package org.coryd.payment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.exception.BadRequestException;
import org.coryd.payment.entity.DriverEarningsAccount;
import org.coryd.payment.entity.PayoutRecipient;
import org.coryd.payment.repository.DriverEarningsAccountRepository;
import org.coryd.payment.repository.PayoutRecipientRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

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

    /** Off by default so an existing deployment keeps working until every driver has a subaccount. */
    @Value("${payment.split.enabled:false}")
    private boolean splitEnabled;

    public Split resolve(String driverId, BigDecimal fare) {
        if (!splitEnabled) {
            return new Split(null, null);
        }

        PayoutRecipient recipient = payoutRecipientRepository
                .findFirstByDriverIdAndIsDefaultTrueAndGatewaySubaccountCodeIsNotNull(driverId)
                .orElseThrow(() -> new BadRequestException(
                        "This driver has not finished setting up payouts, so this trip cannot be booked yet"));

        return new Split(recipient.getGatewaySubaccountCode(), debtChargeKobo(driverId, fare));
    }

    /**
     * A driver in debt has their whole fare routed to us until it clears, rather than a fixed
     * instalment - the alternative is carrying the debt across trips that may never happen.
     */
    private Long debtChargeKobo(String driverId, BigDecimal fare) {
        BigDecimal debt = earningsAccountRepository.findByDriverId(driverId)
                .map(DriverEarningsAccount::getSettlementDebt)
                .orElse(BigDecimal.ZERO);
        if (debt == null || debt.signum() <= 0) {
            return null;
        }
        log.info("Driver {} owes {} - taking the full fare on this booking to recover it", driverId, debt);
        return fare.movePointRight(2).longValueExact();
    }

    public record Split(String subaccountCode, Long flatChargeKobo) {
    }
}
