package org.coryd.payment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.payment.config.CommissionProperties;
import org.coryd.payment.entity.PayoutRecipient;
import org.coryd.payment.gateway.PaystackGatewayClient;
import org.coryd.payment.repository.PayoutRecipientRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Every driver who added a bank account before split payments existed has no subaccount, and a
 * driver without one cannot be booked at all. This gives them one on startup.
 *
 * <p>It then puts our current commission on every subaccount that doesn't carry it yet - the ones
 * created with the driver's share by mistake, and all of them after COMMISSION_PERCENTAGE changes.
 *
 * <p>Runs on every boot and only picks up rows still missing a code or a current split, so it is
 * safe to repeat and self-heals a driver Paystack rejected last time. A failure on one row never
 * stops the rest, and never stops the service starting - the alternative is a bad bank detail
 * keeping payments down.
 */
@Slf4j
@Order
@Component
@RequiredArgsConstructor
public class SubaccountBackfill implements ApplicationRunner {

    private final PayoutRecipientRepository payoutRecipientRepository;
    private final PaystackGatewayClient paystackGatewayClient;
    private final CommissionProperties commissionProperties;

    @Override
    public void run(ApplicationArguments args) {
        createMissing();
        syncSplits();
    }

    private void createMissing() {
        List<PayoutRecipient> pending = payoutRecipientRepository
                .findByGatewaySubaccountCodeIsNull();
        if (pending.isEmpty()) {
            return;
        }

        int created = 0;
        for (PayoutRecipient recipient : pending) {
            try {
                PaystackGatewayClient.SubaccountResult subaccount = paystackGatewayClient.createSubaccount(
                        recipient.getAccountName(), recipient.getAccountNumber(), recipient.getBankCode(),
                        commissionProperties.paystackPercentageCharge());
                recipient.setGatewaySubaccountCode(subaccount.code());
                recipient.setGatewaySubaccountId(subaccount.id());
                recipient.setGatewaySplitPercentage(commissionProperties.paystackPercentageCharge());
                payoutRecipientRepository.save(recipient);
                created++;
            } catch (Exception e) {
                log.error("Could not create a subaccount for driver {} - their routes cannot be booked until this succeeds: {}",
                        recipient.getDriverId(), e.getMessage());
            }
        }
        log.info("Subaccount backfill: created {} of {}", created, pending.size());
    }

    /** In test mode every driver shares one subaccount, so each code is updated once. */
    private void syncSplits() {
        BigDecimal current = commissionProperties.paystackPercentageCharge();
        List<PayoutRecipient> stale = payoutRecipientRepository.findByGatewaySubaccountCodeIsNotNull().stream()
                .filter(r -> r.getGatewaySplitPercentage() == null || r.getGatewaySplitPercentage().compareTo(current) != 0)
                .toList();
        if (stale.isEmpty()) {
            return;
        }

        Map<String, Boolean> updatedByCode = new HashMap<>();
        for (PayoutRecipient recipient : stale) {
            boolean updated = updatedByCode.computeIfAbsent(recipient.getGatewaySubaccountCode(), code -> {
                try {
                    paystackGatewayClient.updateSubaccountPercentage(code, current);
                    return true;
                } catch (Exception e) {
                    log.error("Could not set our {}% on subaccount {} (driver {}) - its fares split the old way until this succeeds: {}",
                            current, code, recipient.getDriverId(), e.getMessage());
                    return false;
                }
            });
            if (updated) {
                recipient.setGatewaySplitPercentage(current);
                payoutRecipientRepository.save(recipient);
            }
        }
        log.info("Subaccount split sync: {} of {} subaccounts now take our {}%",
                updatedByCode.values().stream().filter(Boolean::booleanValue).count(), updatedByCode.size(), current);
    }
}
