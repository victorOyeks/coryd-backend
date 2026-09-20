package org.coryd.profile.service;

import lombok.RequiredArgsConstructor;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.common.kafka.KafkaTopics;
import org.coryd.common.kafka.events.RiderProfileCreatedEvent;
import org.coryd.profile.client.IdentityServiceClient;
import org.coryd.profile.dto.BillingContactResponse;
import org.coryd.profile.dto.RiderProfileResponse;
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
    private final IdentityServiceClient identityServiceClient;

    /** Internal/batch use (RiderProfileController.get, getBatch below) - no phone, no extra cross-service call. */
    public RiderProfile get(String userId) {
        return riderProfileRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Rider profile not found"));
    }

    /** What payment-service puts on the Paystack checkout. Tolerates a missing profile - a rider can pay before ever saving one. */
    @Transactional(readOnly = true)
    public BillingContactResponse getBillingContact(String userId) {
        String email = riderProfileRepository.findByUserId(userId)
                .map(RiderProfile::getEmail)
                .orElse(null);
        return new BillingContactResponse(email, identityServiceClient.fetchPhone(userId));
    }

    /** Batch lookup for trip-service's Trip Receipt screen - one call per receipt, not one per passenger. */
    public List<RiderSummary> getBatch(List<String> userIds) {
        return riderProfileRepository.findByUserIdIn(userIds).stream()
                .map(RiderSummary::from)
                .toList();
    }

    /** The rider's own Profile screen - the one place phone belongs alongside everything else, since RiderProfile itself never stores it. */
    public RiderProfileResponse getMine(String userId) {
        return toResponse(get(userId));
    }

    /** Publishes RiderProfileCreatedEvent only the first time this profile is created, so trip-service seeds this rider's system starting rating exactly once, not on every profile edit. */
    @Transactional
    public RiderProfileResponse updateMine(String userId, UpdateRiderProfileRequest request) {
        boolean isNewProfile = false;
        RiderProfile profile = riderProfileRepository.findByUserId(userId).orElse(null);
        if (profile == null) {
            isNewProfile = true;
            profile = RiderProfile.builder().userId(userId).build();
        }

        profile.setFirstName(request.firstName());
        profile.setLastName(request.lastName());
        profile.setEmail(request.email());
        profile.setPhotoUrl(request.photoUrl());
        profile.setEmergencyContactName(request.emergencyContactName());
        profile.setEmergencyContactPhone(request.emergencyContactPhone());

        RiderProfile saved = riderProfileRepository.save(profile);
        identityServiceClient.syncName(userId, saved.getFirstName(), saved.getLastName());
        if (isNewProfile) {
            kafkaTemplate.send(KafkaTopics.RIDER_PROFILE_CREATED, RiderProfileCreatedEvent.builder().riderId(userId).build());
        }
        return toResponse(saved);
    }

    private RiderProfileResponse toResponse(RiderProfile profile) {
        return RiderProfileResponse.from(profile, identityServiceClient.fetchPhone(profile.getUserId()));
    }
}
