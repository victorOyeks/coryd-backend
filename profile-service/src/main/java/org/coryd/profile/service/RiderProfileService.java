package org.coryd.profile.service;

import lombok.RequiredArgsConstructor;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.common.kafka.KafkaTopics;
import org.coryd.common.kafka.events.RiderProfileCreatedEvent;
import org.coryd.profile.dto.RiderSummary;
import org.coryd.profile.dto.UpdateRiderProfileRequest;
import org.coryd.profile.entity.RiderProfile;
import org.coryd.profile.repository.RiderProfileRepository;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class RiderProfileService {

    private final RiderProfileRepository riderProfileRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public RiderProfile get(String userId) {
        return riderProfileRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Rider profile not found"));
    }

    /** Batch lookup for trip-service's Trip Receipt screen - one call per receipt, not one per passenger. */
    public List<RiderSummary> getBatch(List<String> userIds) {
        return riderProfileRepository.findByUserIdIn(userIds).stream()
                .map(RiderSummary::from)
                .toList();
    }

    /** Publishes RiderProfileCreatedEvent only the first time this profile is created, so trip-service seeds this rider's system starting rating exactly once, not on every profile edit. */
    @Transactional
    public RiderProfile updateMine(String userId, UpdateRiderProfileRequest request) {
        boolean isNewProfile = false;
        RiderProfile profile = riderProfileRepository.findByUserId(userId).orElse(null);
        if (profile == null) {
            isNewProfile = true;
            profile = RiderProfile.builder().userId(userId).build();
        }

        profile.setFullName(request.fullName());
        profile.setEmail(request.email());
        profile.setPhotoUrl(request.photoUrl());
        profile.setEmergencyContactName(request.emergencyContactName());
        profile.setEmergencyContactPhone(request.emergencyContactPhone());

        RiderProfile saved = riderProfileRepository.save(profile);
        if (isNewProfile) {
            kafkaTemplate.send(KafkaTopics.RIDER_PROFILE_CREATED, RiderProfileCreatedEvent.builder().riderId(userId).build());
        }
        return saved;
    }
}
