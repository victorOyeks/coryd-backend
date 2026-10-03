package org.coryd.profile.dto;

import org.coryd.common.enums.VerificationStatus;
import org.coryd.profile.entity.DriverProfile;
import org.coryd.profile.entity.EmergencyContact;

import java.time.Instant;
import java.time.LocalDate;

/** GET/PUT /drivers/me's actual response shape - everything DriverProfile has, plus phone
 * (fetched from identity-service - DriverProfile itself never stores it). */
public record DriverProfileResponse(
        String id,
        String customId,
        Instant createdAt,
        Instant updatedAt,
        String userId,
        String firstName,
        String lastName,
        String photoUrl,
        String email,
        String phone,
        LocalDate dateOfBirth,
        String governmentIdUrl,
        String idNumber,
        VerificationStatus governmentIdStatus,
        String governmentIdRejectionReason,
        Instant verifiedSince,
        String emergencyContactName,
        String emergencyContactPhone
) {
    public static DriverProfileResponse from(DriverProfile profile, String phone, EmergencyContact primaryContact) {
        return new DriverProfileResponse(
                profile.getId(), profile.getCustomId(), profile.getCreatedAt(), profile.getUpdatedAt(),
                profile.getUserId(), profile.getFirstName(), profile.getLastName(), profile.getPhotoUrl(), profile.getEmail(), phone,
                profile.getDateOfBirth(), profile.getGovernmentIdUrl(), profile.getIdNumber(),
                profile.getGovernmentIdStatus(), profile.getGovernmentIdRejectionReason(),
                profile.getVerifiedSince(),
                primaryContact == null ? null : primaryContact.getName(),
                primaryContact == null ? null : primaryContact.getPhone()
        );
    }
}
