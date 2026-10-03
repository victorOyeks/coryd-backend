package org.coryd.payment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.payment.config.CommissionProperties;
import org.coryd.payment.entity.PayoutRecipient;
import org.coryd.payment.gateway.PaystackGatewayClient;
import org.coryd.payment.repository.PayoutRecipientRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Every driver who added a bank account before split payments existed has no subaccount, and with
 * splits on a driver without one cannot be booked at all. This gives them one on startup.
 *
 * <p>Runs on every boot and only picks up rows still missing a code, so it is safe to repeat and
 * self-heals a driver Paystack rejected last time. A failure on one row never stops the rest, and
 * never stops the service starting - the alternative is a bad bank detail keeping payments down.
 */
@Slf4j
@Order
@Component
@RequiredArgsConstructor
public class SubaccountBackfill implements ApplicationRunner {

    private final PayoutRecipientRepository payoutRecipientRepository;
    private final PaystackGatewayClient paystackGatewayClient;
    private final CommissionProperties commissionProperties;

    @Value("${payment.split.enabled:false}")
    private boolean splitEnabled;

    @Override
    public void run(ApplicationArguments args) {
        if (!splitEnabled) {
            return;
        }
        List<PayoutRecipient> pending = payoutRecipientRepository
                .findByGatewaySubaccountCodeIsNullAndGatewayRecipientCodeIsNotNull();
        if (pending.isEmpty()) {
            return;
        }

        int created = 0;
        for (PayoutRecipient recipient : pending) {
            try {
                recipient.setGatewaySubaccountCode(paystackGatewayClient.createSubaccount(
                        recipient.getAccountName(), recipient.getAccountNumber(), recipient.getBankCode(),
                        commissionProperties.driverSharePercentage()));
                payoutRecipientRepository.save(recipient);
                created++;
            } catch (Exception e) {
                log.error("Could not create a subaccount for driver {} - their routes cannot be booked until this succeeds: {}",
                        recipient.getDriverId(), e.getMessage());
            }
        }
        log.info("Subaccount backfill: created {} of {}", created, pending.size());
    }
}
