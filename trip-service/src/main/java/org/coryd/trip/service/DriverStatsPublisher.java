package org.coryd.trip.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.kafka.KafkaTopics;
import org.coryd.common.kafka.events.DriverStatsChangedEvent;
import org.coryd.trip.dto.OpsDriverStats;
import org.coryd.trip.repository.RoutePostRepository;
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

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Keeps the Ops Console's driver index (in profile-service) in step with each driver's trips, rating, reliability and
 * reports. A change is sent once its transaction commits, once per driver however many changes it made. A nightly
 * pass sends every driver again, so nothing missed stays wrong for long.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DriverStatsPublisher {

    static final int BATCH = 200;
    private static final String PENDING = DriverStatsPublisher.class.getName() + ".pending";

    private final OpsDriverStatsService statsService;
    private final RoutePostRepository routePostRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${trip.driver-stats.sync-on-startup:true}")
    private boolean syncOnStartup;

    /** Something this driver's numbers depend on changed - send them after commit. */
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

    /** Best effort - the nightly pass sends anything that didn't go. */
    void publish(Collection<String> driverIds) {
        try {
            Instant now = Instant.now();
            for (OpsDriverStats s : statsService.stats(driverIds)) {
                kafkaTemplate.send(KafkaTopics.DRIVER_STATS_CHANGED, s.driverId(), DriverStatsChangedEvent.builder()
                        .driverId(s.driverId()).trips(s.trips()).ratingAvg(s.ratingAvg()).ratingCount(s.ratingCount())
                        .reliabilityPercent(s.reliabilityPercent()).reportsFiled(s.reportsFiled()).lastTripAt(s.lastTripAt())
                        .computedAt(now).build());
            }
        } catch (Exception e) {
            log.warn("Could not send driver stats for {}: {}", driverIds, e.getMessage());
        }
    }

    /** Every driver who has ever posted a trip, a page at a time. */
    @Scheduled(cron = "${trip.driver-stats.sync-cron:0 30 2 * * *}", zone = "Africa/Lagos")
    public int syncAll() {
        int sent = 0;
        Slice<String> page = routePostRepository.findDistinctDriverIds(PageRequest.of(0, BATCH));
        while (true) {
            if (!page.getContent().isEmpty()) {
                publish(page.getContent());
                sent += page.getNumberOfElements();
            }
            if (!page.hasNext()) {
                break;
            }
            page = routePostRepository.findDistinctDriverIds(page.nextPageable());
        }
        log.info("Driver stats sync: sent {} driver(s)", sent);
        return sent;
    }

    /** Fills the index on a fresh deploy without waiting for the night. Off once it's populated, if startup time matters. */
    @EventListener(ApplicationReadyEvent.class)
    public void syncOnStartup() {
        if (syncOnStartup) {
            Thread.ofVirtual().name("driver-stats-sync").start(() -> {
                try {
                    syncAll();
                } catch (Exception e) {
                    log.warn("Driver stats startup sync failed: {}", e.getMessage());
                }
            });
        }
    }
}
