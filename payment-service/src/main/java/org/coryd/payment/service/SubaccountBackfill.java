package org.coryd.payment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.payment.config.CommissionProperties;
import org.coryd.payment.entity.PayoutRecipient;
import org.coryd.payment.gateway.PaystackGatewayClient;
import org.coryd.payment.gateway.PaystackProperties;
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
 * <p>It then puts our current commission and payout schedule on every subaccount that doesn't carry
 * them yet - the ones created with the driver's share by mistake, and all of them after
 * COMMISSION_PERCENTAGE or PAYSTACK_SETTLEMENT_SCHEDULE changes.
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
    private final PaystackProperties paystackProperties;

    @Override
    public void run(ApplicationArguments args) {
        createMissing();
        syncSubaccounts();
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
                recipient.setGatewaySettlementSchedule(paystackProperties.getSettlementSchedule());
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
    private void syncSubaccounts() {
        BigDecimal current = commissionProperties.paystackPercentageCharge();
        String schedule = paystackProperties.getSettlementSchedule();
        List<PayoutRecipient> stale = payoutRecipientRepository.findByGatewaySubaccountCodeIsNotNull().stream()
                .filter(r -> r.getGatewaySplitPercentage() == null || r.getGatewaySplitPercentage().compareTo(current) != 0
                        || !schedule.equalsIgnoreCase(r.getGatewaySettlementSchedule()))
                .toList();
        if (stale.isEmpty()) {
            return;
        }

        Map<String, Boolean> updatedByCode = new HashMap<>();
        for (PayoutRecipient recipient : stale) {
            boolean updated = updatedByCode.computeIfAbsent(recipient.getGatewaySubaccountCode(), code -> {
                try {
                    paystackGatewayClient.updateSubaccount(code, current, schedule);
                    return true;
                } catch (Exception e) {
                    log.error("Could not set our {}% and {} payouts on subaccount {} (driver {}) - it keeps its old split and schedule until this succeeds: {}",
                            current, schedule, code, recipient.getDriverId(), e.getMessage());
                    return false;
                }
            });
            if (updated) {
                recipient.setGatewaySplitPercentage(current);
                recipient.setGatewaySettlementSchedule(schedule);
                payoutRecipientRepository.save(recipient);
            }
        }
        log.info("Subaccount sync: {} of {} subaccounts now take our {}% and pay out {}",
                updatedByCode.values().stream().filter(Boolean::booleanValue).count(), updatedByCode.size(), current, schedule);
    }
}
