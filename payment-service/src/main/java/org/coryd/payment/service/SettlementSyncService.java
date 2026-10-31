package org.coryd.payment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.payment.dto.SettlementStatus;
import org.coryd.payment.entity.DriverSettlement;
import org.coryd.payment.entity.PayoutRecipient;
import org.coryd.payment.gateway.PaystackGatewayClient;
import org.coryd.payment.repository.DriverSettlementRepository;
import org.coryd.payment.repository.PayoutRecipientRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Mirrors Paystack's settlements to each driver's bank, so the earnings screen can show what has
 * been paid and what is on its way. Paystack is the record; this only copies it. One driver failing
 * never stops the rest.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SettlementSyncService {

    /** Payouts settle within days, so a few months back covers every status that can still change. */
    private static final Duration LOOKBACK = Duration.ofDays(90);
    private static final int PER_PAGE = 100;
    private static final int MAX_PAGES = 10;

    private final PayoutRecipientRepository payoutRecipientRepository;
    private final DriverSettlementRepository driverSettlementRepository;
    private final PaystackGatewayClient paystackGatewayClient;

    @Scheduled(cron = "${payment.settlement-sync.cron:0 15 */6 * * *}")
    public void syncAll() {
        List<PayoutRecipient> recipients = payoutRecipientRepository.findByGatewaySubaccountCodeIsNotNull();
        int synced = 0;
        for (PayoutRecipient recipient : recipients) {
            try {
                synced += sync(recipient);
            } catch (Exception e) {
                log.warn("Could not sync settlements for driver {}: {}", recipient.getDriverId(), e.getMessage());
            }
        }
        if (synced > 0) {
            log.info("Settlement sync: {} settlement(s) across {} subaccount(s)", synced, recipients.size());
        }
    }

    int sync(PayoutRecipient recipient) {
        String subaccountId = recipient.getGatewaySubaccountId();
        if (subaccountId == null) {
            subaccountId = paystackGatewayClient.fetchSubaccountId(recipient.getGatewaySubaccountCode());
            if (subaccountId == null) {
                return 0;
            }
            recipient.setGatewaySubaccountId(subaccountId);
            payoutRecipientRepository.save(recipient);
        }

        Instant from = Instant.now().minus(LOOKBACK);
        int count = 0;
        for (int page = 1; page <= MAX_PAGES; page++) {
            List<PaystackGatewayClient.Settlement> settlements = paystackGatewayClient.listSettlements(subaccountId, from, page, PER_PAGE);
            settlements.forEach(s -> upsert(recipient.getDriverId(), s));
            count += settlements.size();
            if (settlements.size() < PER_PAGE) {
                break;
            }
        }
        return count;
    }

    private void upsert(String driverId, PaystackGatewayClient.Settlement settlement) {
        if (settlement.id() == null) {
            return;
        }
        Long kobo = settlement.effectiveAmount() != null ? settlement.effectiveAmount() : settlement.totalAmount();
        DriverSettlement row = driverSettlementRepository.findByGatewaySettlementId(settlement.id())
                .orElseGet(() -> DriverSettlement.builder().driverId(driverId).gatewaySettlementId(settlement.id()).build());
        row.setAmount(kobo == null ? BigDecimal.ZERO : BigDecimal.valueOf(kobo).movePointLeft(2));
        row.setStatus(SettlementStatus.fromGateway(settlement.status()).name().toLowerCase(Locale.ROOT));
        row.setSettlementDate(parse(settlement.settlementDate()));
        try {
            driverSettlementRepository.save(row);
        } catch (DataIntegrityViolationException e) {
            // Another instance inserted it first - the next sync updates it.
        }
    }

    private static Instant parse(String value) {
        try {
            return value == null ? null : Instant.parse(value);
        } catch (Exception e) {
            return null;
        }
    }
}
