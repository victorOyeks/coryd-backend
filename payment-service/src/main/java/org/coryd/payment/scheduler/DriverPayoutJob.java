package org.coryd.payment.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.enums.PayoutStatus;
import org.coryd.common.kafka.KafkaTopics;
import org.coryd.common.kafka.events.PayoutProcessedEvent;
import org.coryd.payment.entity.DriverEarningsAccount;
import org.coryd.payment.entity.Payout;
import org.coryd.payment.repository.DriverEarningsAccountRepository;
import org.coryd.payment.repository.PayoutRepository;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * D-502: payout schedule is a Product/Finance decision left open by the PRD -
 * defaults to a nightly batch here, tunable via the cron expression.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DriverPayoutJob {

    private final DriverEarningsAccountRepository earningsAccountRepository;
    private final PayoutRepository payoutRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Scheduled(cron = "${payout.cron:0 0 4 * * *}")
    @Transactional
    public void runPayoutBatch() {
        List<DriverEarningsAccount> accounts = earningsAccountRepository.findAll().stream()
                .filter(a -> a.getAvailableBalance().compareTo(BigDecimal.ZERO) > 0)
                .toList();

        for (DriverEarningsAccount account : accounts) {
            BigDecimal amount = account.getAvailableBalance();

            Payout payout = payoutRepository.save(Payout.builder()
                    .driverId(account.getDriverId())
                    .amount(amount)
                    .status(PayoutStatus.PAID) // gateway payout call would sit here; stubbed for the pilot scaffold
                    .build());

            account.setAvailableBalance(BigDecimal.ZERO);
            earningsAccountRepository.save(account);

            kafkaTemplate.send(KafkaTopics.PAYOUT_PROCESSED, PayoutProcessedEvent.builder()
                    .payoutId(payout.getId().toString())
                    .driverId(account.getDriverId().toString())
                    .amount(amount)
                    .processedAt(LocalDateTime.now())
                    .build());
        }

        if (!accounts.isEmpty()) {
            log.info("Processed payouts for {} drivers", accounts.size());
        }
    }
}
