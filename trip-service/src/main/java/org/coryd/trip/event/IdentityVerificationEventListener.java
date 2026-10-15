package org.coryd.trip.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.kafka.KafkaTopics;
import org.coryd.common.kafka.events.IdentityVerificationChangedEvent;
import org.coryd.trip.config.KafkaConfig;
import org.coryd.trip.entity.IdentityVerificationCache;
import org.coryd.trip.repository.IdentityVerificationCacheRepository;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class IdentityVerificationEventListener {

    private final IdentityVerificationCacheRepository cacheRepository;

    @KafkaListener(topics = KafkaTopics.IDENTITY_VERIFICATION_CHANGED, groupId = KafkaConfig.GROUP_ID)
    @Transactional
    public void onChanged(IdentityVerificationChangedEvent event) {
        cacheRepository.save(new IdentityVerificationCache(event.getUserId(), event.isVerified(), event.getDecidedAt()));
        log.info("User {} identity verification cache set to verified={}", event.getUserId(), event.isVerified());
    }
}
