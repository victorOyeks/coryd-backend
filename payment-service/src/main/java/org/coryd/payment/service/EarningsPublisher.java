package org.coryd.payment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.kafka.KafkaTopics;
import org.coryd.common.kafka.events.DriverEarningsChangedEvent;
import org.coryd.payment.entity.DriverEarningsAccount;
import org.coryd.payment.repository.DriverEarningsAccountRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Keeps the Ops Console's driver index (in profile-service) in step with each driver's lifetime takings. A change is
 * sent once its transaction commits, once per driver; a nightly pass sends every driver again.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EarningsPublisher {

    static final int BATCH = 200;
    private static final String PENDING = EarningsPublisher.class.getName() + ".pending";

    private final DriverEarningsAccountRepository earningsAccountRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${payment.earnings-sync.on-startup:true}")
    private boolean syncOnStartup;

    /** This driver's takings changed - send them after commit. */
    public void changed(String driverId) {
        if (driverId == null) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            publish(List.of(driverId));
            return;
        }
        @SuppressWarnings("unchecked")
        Set<String> pending = (Set<String>) TransactionSynchronizationManager.getResource(PENDING);
        if (pending == null) {
            Set<String> ids = new LinkedHashSet<>();
            TransactionSynchronizationManager.bindResource(PENDING, ids);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    TransactionSynchronizationManager.unbindResourceIfPossible(PENDING);
                    if (status == STATUS_COMMITTED) {
                        publish(ids);
                    }
                }
            });
            pending = ids;
        }
        pending.add(driverId);
    }

    void publish(Collection<String> driverIds) {
        try {
            earningsAccountRepository.findByDriverIdIn(driverIds).forEach(this::send);
        } catch (Exception e) {
            log.warn("Could not send earnings for {}: {}", driverIds, e.getMessage());
        }
    }

    private void send(DriverEarningsAccount account) {
        BigDecimal gross = account.getLifetimeGross() == null ? BigDecimal.ZERO : account.getLifetimeGross();
        BigDecimal commission = account.getLifetimeCommission() == null ? BigDecimal.ZERO : account.getLifetimeCommission();
        kafkaTemplate.send(KafkaTopics.DRIVER_EARNINGS_CHANGED, account.getDriverId(), DriverEarningsChangedEvent.builder()
                .driverId(account.getDriverId()).lifetimeGross(gross).lifetimeNet(gross.subtract(commission))
                .computedAt(Instant.now()).build());
    }

    @Scheduled(cron = "${payment.earnings-sync.cron:0 40 2 * * *}", zone = "Africa/Lagos")
    public int syncAll() {
        int sent = 0;
        Slice<DriverEarningsAccount> page = earningsAccountRepository.findAllBy(PageRequest.of(0, BATCH));
        while (true) {
            for (DriverEarningsAccount account : page.getContent()) {
                try {
                    send(account);
                    sent++;
                } catch (Exception e) {
                    log.warn("Could not send earnings for {}: {}", account.getDriverId(), e.getMessage());
                }
            }
            if (!page.hasNext()) {
                break;
            }
            page = earningsAccountRepository.findAllBy(page.nextPageable());
        }
        log.info("Driver earnings sync: sent {} driver(s)", sent);
        return sent;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void syncOnStartup() {
        if (syncOnStartup) {
            Thread.ofVirtual().name("driver-earnings-sync").start(() -> {
                try {
                    syncAll();
                } catch (Exception e) {
                    log.warn("Driver earnings startup sync failed: {}", e.getMessage());
                }
            });
        }
    }
}
