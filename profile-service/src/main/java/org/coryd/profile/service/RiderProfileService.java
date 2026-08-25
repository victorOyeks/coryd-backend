package org.coryd.profile.service;

import lombok.RequiredArgsConstructor;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.profile.dto.RiderSummary;
import org.coryd.profile.dto.UpdateRiderProfileRequest;
import org.coryd.profile.entity.RiderProfile;
import org.coryd.profile.repository.RiderProfileRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class RiderProfileService {

    private final RiderProfileRepository riderProfileRepository;

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

    @Transactional
    public RiderProfile updateMine(String userId, UpdateRiderProfileRequest request) {
        RiderProfile profile = riderProfileRepository.findByUserId(userId)
                .orElseGet(() -> RiderProfile.builder().userId(userId).build());

        profile.setFullName(request.fullName());
        profile.setEmail(request.email());
        profile.setPhotoUrl(request.photoUrl());
        profile.setEmergencyContactName(request.emergencyContactName());
        profile.setEmergencyContactPhone(request.emergencyContactPhone());

        return riderProfileRepository.save(profile);
    }
}
