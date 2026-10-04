package org.coryd.payment.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.payment.repository.FareHoldRepository;
import org.coryd.payment.service.EarningsService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Keeps retrying refunds Paystack refused or couldn't be reached for, so a rider is never left owed. */
@Slf4j
@Component
@RequiredArgsConstructor
public class RefundRetryJob {

    private final FareHoldRepository fareHoldRepository;
    private final EarningsService earningsService;

    @Scheduled(fixedDelayString = "${payment.refund-retry.delay-ms:900000}", initialDelay = 60_000)
    public void retry() {
        for (String holdId : fareHoldRepository.findIdsWithRefundDue()) {
            try {
                earningsService.retryRefund(holdId);
            } catch (Exception e) {
                log.warn("Refund retry failed for hold {}: {}", holdId, e.getMessage());
            }
        }
    }
}
