package org.coryd.payment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.payment.entity.PayoutRecipient;
import org.coryd.payment.repository.PayoutRecipientRepository;
import org.springframework.stereotype.Component;

/** Re-reads one driver's settlements from Paystack now, instead of waiting for the six-hourly sync - for the Ops Console. */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpsSettlementResync {

    private final SettlementSyncService settlementSyncService;
    private final PayoutRecipientRepository payoutRecipientRepository;

    /** Best effort - a failure only means the next scheduled sync brings it in. */
    public int resync(String driverId) {
        int count = 0;
        for (PayoutRecipient recipient : payoutRecipientRepository.findByDriverIdOrderByCreatedAtDesc(driverId)) {
            if (recipient.getGatewaySubaccountCode() == null) {
                continue;
            }
            try {
                count += settlementSyncService.sync(recipient);
            } catch (Exception e) {
                log.warn("Could not re-sync settlements for driver {}: {}", driverId, e.getMessage());
            }
        }
        return count;
    }
}
