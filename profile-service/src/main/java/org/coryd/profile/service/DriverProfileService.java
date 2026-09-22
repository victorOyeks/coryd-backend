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
    private final PremblyVerificationService premblyVerificationService;

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

    /** Public, rider-facing single-driver lookup (e.g. a driver-profile card reached from chat or
     * the booking manifest) - deliberately the same safe-to-show-another-user projection as
     * getBatch, not the raw DriverProfile get() returns. That entity carries dateOfBirth, email,
     * governmentIdUrl and idNumber - fine for the driver's own /me screen or a service-to-service
     * call, not for any authenticated rider who happens to know another driver's userId. */
    public DriverSummary getSummary(String userId) {
        return DriverSummary.from(get(userId));
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

        profile.setFirstName(request.firstName());
        profile.setLastName(request.lastName());
        profile.setPhotoUrl(request.photoUrl());
        profile.setEmail(request.email());
        profile.setDateOfBirth(request.dateOfBirth());
        profile.setIdNumber(request.idNumber());

        // Resubmitting the license photo resets just that
        // item's own review back to PENDING - the other stays as it was.
        if (request.governmentIdUrl() != null) {
            profile.setGovernmentIdUrl(request.governmentIdUrl());
            profile.setGovernmentIdStatus(VerificationStatus.IN_REVIEW);
            profile.setGovernmentIdRejectionReason(null);
        }

        DriverProfile saved = driverProfileRepository.save(profile);
        identityServiceClient.syncName(userId, saved.getFirstName(), saved.getLastName());
        // Off-thread and idempotent - re-saving an unchanged licence number spends no lookup.
        premblyVerificationService.checkDriversLicence(
                userId, saved.getIdNumber(), saved.getFirstName(), saved.getLastName());
        if (isNewProfile) {
            kafkaTemplate.send(KafkaTopics.DRIVER_PROFILE_CREATED, DriverProfileCreatedEvent.builder().driverId(userId).build());
        }
        return toResponse(saved);
    }

    private DriverProfileResponse toResponse(DriverProfile profile) {
        return DriverProfileResponse.from(profile, identityServiceClient.fetchPhone(profile.getUserId()));
    }
}
