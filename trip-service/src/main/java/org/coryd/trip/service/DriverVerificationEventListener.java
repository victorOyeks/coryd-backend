package org.coryd.trip.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.kafka.events.DriverApprovedEvent;
import org.coryd.common.kafka.events.DriverRejectedEvent;
import org.coryd.trip.entity.DriverVerificationCache;
import org.coryd.trip.repository.DriverVerificationCacheRepository;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;


@Slf4j
@Component
@RequiredArgsConstructor
public class DriverVerificationEventListener {

    private final DriverVerificationCacheRepository cacheRepository;

    @KafkaListener(topics = "coryd-driver-approved", groupId = "coryd-trip-service")
    @Transactional
    public void onApproved(DriverApprovedEvent event) {
        upsert(event.getDriverId(), true);
    }

    @KafkaListener(topics = "coryd-driver-rejected", groupId = "coryd-trip-service")
    @Transactional
    public void onRejected(DriverRejectedEvent event) {
        upsert(event.getDriverId(), false);
    }

    private void upsert(String driverId, boolean approved) {
        DriverVerificationCache cache = cacheRepository.findById(driverId)
                .orElseGet(() -> new DriverVerificationCache(driverId, approved));
        cache.setApproved(approved);
        cacheRepository.save(cache);
        log.info("Driver {} verification cache set to approved={}", driverId, approved);
    }
}
