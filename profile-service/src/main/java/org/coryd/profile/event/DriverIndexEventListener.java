package org.coryd.profile.event;

import lombok.RequiredArgsConstructor;
import org.coryd.common.kafka.KafkaTopics;
import org.coryd.common.kafka.events.DriverEarningsChangedEvent;
import org.coryd.common.kafka.events.DriverStatsChangedEvent;
import org.coryd.common.kafka.events.RiderStatsChangedEvent;
import org.coryd.common.kafka.events.UserReinstatedEvent;
import org.coryd.common.kafka.events.UserSuspendedEvent;
import org.coryd.profile.service.DriverIndexService;
import org.coryd.profile.service.RiderIndexService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Trip, payment and identity numbers onto driver_index and rider_index as they change. A suspension goes to both from
 * the one listener - two listeners on a topic in the same consumer group would each get only some of the messages.
 */
@Component
@RequiredArgsConstructor
public class DriverIndexEventListener {

    private final DriverIndexService driverIndexService;
    private final RiderIndexService riderIndexService;

    @KafkaListener(topics = KafkaTopics.DRIVER_STATS_CHANGED)
    public void onStats(DriverStatsChangedEvent event) {
        driverIndexService.applyStats(event);
    }

    @KafkaListener(topics = KafkaTopics.RIDER_STATS_CHANGED)
    public void onRiderStats(RiderStatsChangedEvent event) {
        riderIndexService.applyStats(event);
    }

    @KafkaListener(topics = KafkaTopics.DRIVER_EARNINGS_CHANGED)
    public void onEarnings(DriverEarningsChangedEvent event) {
        driverIndexService.applyEarnings(event);
    }

    @KafkaListener(topics = KafkaTopics.USER_SUSPENDED)
    public void onSuspended(UserSuspendedEvent event) {
        java.time.Instant at = event.getSuspendedAt() == null ? java.time.Instant.now() : event.getSuspendedAt();
        driverIndexService.applySuspension(event.getUserId(), at, event.getReason());
        riderIndexService.applySuspension(event.getUserId(), at, event.getReason());
    }

    @KafkaListener(topics = KafkaTopics.USER_REINSTATED)
    public void onReinstated(UserReinstatedEvent event) {
        driverIndexService.applySuspension(event.getUserId(), null, null);
        riderIndexService.applySuspension(event.getUserId(), null, null);
    }
}
