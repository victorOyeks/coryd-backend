package org.coryd.profile.dto;

import org.coryd.profile.entity.RiderProfile;

import java.time.Instant;

/** GET/PUT /riders/me's actual response shape - everything RiderProfile has, plus phone
 * (fetched from identity-service - RiderProfile itself never stores it). */
public record RiderProfileResponse(
        String id,
        String customId,
        Instant createdAt,
        Instant updatedAt,
        String userId,
        String fullName,
        String email,
        String phone,
        String photoUrl,
        String emergencyContactName,
        String emergencyContactPhone
) {
    public static RiderProfileResponse from(RiderProfile profile, String phone) {
        return new RiderProfileResponse(
                profile.getId(), profile.getCustomId(), profile.getCreatedAt(), profile.getUpdatedAt(),
                profile.getUserId(), profile.getFullName(), profile.getEmail(), phone, profile.getPhotoUrl(),
                profile.getEmergencyContactName(), profile.getEmergencyContactPhone()
        );
    }
}
