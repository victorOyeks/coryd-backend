package org.coryd.trip.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.enums.RatingDirection;
import org.coryd.common.kafka.KafkaTopics;
import org.coryd.common.kafka.events.DriverProfileCreatedEvent;
import org.coryd.common.kafka.events.RiderProfileCreatedEvent;
import org.coryd.trip.config.KafkaConfig;
import org.coryd.trip.entity.Rating;
import org.coryd.trip.repository.RatingRepository;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Every new driver/rider profile starts at a 5-star "rated by system" baseline (no bookingId,
 * no real rater) - real ratings from real people fold into the same simple average afterward via
 * the exact same RatingRepository.summarize() every other rating already goes through, so the
 * system rating isn't a separate "default shown when empty" - it's one real row that counts
 * toward the average forever, same as any other. */
@Slf4j
@Component
@RequiredArgsConstructor
public class RatingSeedEventListener {

    private static final String SYSTEM_RATER_ID = "SYSTEM";
    private static final int STARTING_STARS = 5;

    private final RatingRepository ratingRepository;

    @KafkaListener(topics = KafkaTopics.DRIVER_PROFILE_CREATED, groupId = KafkaConfig.GROUP_ID)
    @Transactional
    public void onDriverProfileCreated(DriverProfileCreatedEvent event) {
        seed(event.getDriverId(), RatingDirection.RIDER_TO_DRIVER);
    }

    @KafkaListener(topics = KafkaTopics.RIDER_PROFILE_CREATED, groupId = KafkaConfig.GROUP_ID)
    @Transactional
    public void onRiderProfileCreated(RiderProfileCreatedEvent event) {
        seed(event.getRiderId(), RatingDirection.DRIVER_TO_RIDER);
    }

    /** Guarded by existsByRateeId, not just "called once" - Kafka's at-least-once delivery means this can fire more than once for the same profile. */
    private void seed(String rateeId, RatingDirection direction) {
        if (ratingRepository.existsByRateeId(rateeId)) {
            return;
        }
        ratingRepository.save(Rating.builder()
                .raterId(SYSTEM_RATER_ID)
                .rateeId(rateeId)
                .direction(direction)
                .stars(STARTING_STARS)
                .build());
        log.info("Seeded starting rating ({} stars) for {}", STARTING_STARS, rateeId);
    }
}
