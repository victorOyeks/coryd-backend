package org.coryd.trip.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.kafka.KafkaTopics;
import org.coryd.common.kafka.events.RiderStatsChangedEvent;
import org.coryd.trip.dto.OpsRiderStats;
import org.coryd.trip.repository.BookingRepository;
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
 * Keeps the Ops Console's rider index (in profile-service) in step with each rider's trips, rating, reliability, reports
 * and spend. Sent once the transaction commits, once per rider; a nightly pass sends every rider again.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RiderStatsPublisher {

    static final int BATCH = 200;
    private static final String PENDING = RiderStatsPublisher.class.getName() + ".pending";

    private final OpsRiderStatsService statsService;
    private final BookingRepository bookingRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${trip.rider-stats.sync-on-startup:true}")
    private boolean syncOnStartup;

    /** Something this rider's numbers depend on changed - send them after commit. */
    public void changed(String riderId) {
        if (riderId == null) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            publish(List.of(riderId));
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
        pending.add(riderId);
    }

    /** Best effort - the nightly pass sends anything that didn't go. */
    void publish(Collection<String> riderIds) {
        try {
            Instant now = Instant.now();
            for (OpsRiderStats s : statsService.stats(riderIds)) {
                kafkaTemplate.send(KafkaTopics.RIDER_STATS_CHANGED, s.riderId(), RiderStatsChangedEvent.builder()
                        .riderId(s.riderId()).trips(s.trips()).ratingAvg(s.ratingAvg()).ratingCount(s.ratingCount())
                        .reliabilityPercent(s.reliabilityPercent()).reportsFiled(s.reportsFiled()).totalSpent(s.totalSpent())
                        .computedAt(now).build());
            }
        } catch (Exception e) {
            log.warn("Could not send rider stats for {}: {}", riderIds, e.getMessage());
        }
    }

    /** Every rider who has ever booked, a page at a time. */
    @Scheduled(cron = "${trip.rider-stats.sync-cron:0 35 2 * * *}", zone = "Africa/Lagos")
    public int syncAll() {
        int sent = 0;
        Slice<String> page = bookingRepository.findDistinctRiderIds(PageRequest.of(0, BATCH));
        while (true) {
            if (!page.getContent().isEmpty()) {
                publish(page.getContent());
                sent += page.getNumberOfElements();
            }
            if (!page.hasNext()) {
                break;
            }
            page = bookingRepository.findDistinctRiderIds(page.nextPageable());
        }
        log.info("Rider stats sync: sent {} rider(s)", sent);
        return sent;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void syncOnStartup() {
        if (syncOnStartup) {
            Thread.ofVirtual().name("rider-stats-sync").start(() -> {
                try {
                    syncAll();
                } catch (Exception e) {
                    log.warn("Rider stats startup sync failed: {}", e.getMessage());
                }
            });
        }
    }
}
