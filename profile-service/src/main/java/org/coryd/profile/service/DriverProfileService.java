package org.coryd.profile.service;

import lombok.RequiredArgsConstructor;
import org.coryd.common.enums.VerificationStatus;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.common.kafka.KafkaTopics;
import org.coryd.common.kafka.events.DriverProfileCreatedEvent;
import org.coryd.profile.client.IdentityServiceClient;
import org.coryd.profile.dto.DriverProfileResponse;
import org.coryd.profile.dto.DriverSummary;
import org.coryd.profile.dto.UpdateDriverProfileRequest;
import org.coryd.profile.entity.DriverProfile;
import org.coryd.profile.repository.DriverProfileRepository;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class DriverProfileService {

    private final DriverProfileRepository driverProfileRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final IdentityServiceClient identityServiceClient;

    /** Internal/batch use (InternalDriverController, getBatch below) - no phone, no extra cross-service call. */
    public DriverProfile get(String userId) {
        return driverProfileRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Driver profile not found"));
    }

    /** Batch lookup for search-result enrichment (trip-service) - one call per result page, not one per driver. */
    public List<DriverSummary> getBatch(List<String> userIds) {
        return driverProfileRepository.findByUserIdIn(userIds).stream()
                .map(DriverSummary::from)
                .toList();
    }

    /** The driver's own Profile screen - the one place phone belongs alongside everything else, since DriverProfile itself never stores it. */
    public DriverProfileResponse getMine(String userId) {
        return toResponse(get(userId));
    }

    /** Upsert: this is now the only place a DriverProfile row is ever created - no event listener creates a shell in the background, so there's no race to reason about here at all. Publishes DriverProfileCreatedEvent only the first time, so trip-service seeds this driver's system starting rating exactly once, not on every profile edit. */
    @Transactional
    public DriverProfileResponse updateMine(String userId, UpdateDriverProfileRequest request) {
        boolean isNewProfile = false;
        DriverProfile profile = driverProfileRepository.findByUserId(userId).orElse(null);
        if (profile == null) {
            isNewProfile = true;
            profile = DriverProfile.builder().userId(userId).build();
        }

        profile.setLegalName(request.legalName());
        profile.setPhotoUrl(request.photoUrl());
        profile.setEmail(request.email());
        profile.setDateOfBirth(request.dateOfBirth());
        profile.setIdNumber(request.idNumber());

        // Resubmitting either the license photo or the selfie resets just that
        // item's own review back to PENDING - the other stays as it was.
        if (request.governmentIdUrl() != null) {
            profile.setGovernmentIdUrl(request.governmentIdUrl());
            profile.setGovernmentIdStatus(VerificationStatus.PENDING);
            profile.setGovernmentIdRejectionReason(null);
        }
        if (request.selfieUrl() != null) {
            profile.setSelfieUrl(request.selfieUrl());
            profile.setSelfiePublicId(request.selfiePublicId());
            profile.setSelfieSubmittedAt(Instant.now());
            profile.setSelfieStatus(VerificationStatus.PENDING);
            profile.setSelfieRejectionReason(null);
        }

        DriverProfile saved = driverProfileRepository.save(profile);
        if (isNewProfile) {
            kafkaTemplate.send(KafkaTopics.DRIVER_PROFILE_CREATED, DriverProfileCreatedEvent.builder().driverId(userId).build());
        }
        return toResponse(saved);
    }

    private DriverProfileResponse toResponse(DriverProfile profile) {
        return DriverProfileResponse.from(profile, identityServiceClient.fetchPhone(profile.getUserId()));
    }
}
