package org.coryd.profile.dto;

import jakarta.validation.constraints.NotBlank;

public record UpdateRiderProfileRequest(
        @NotBlank String firstName,
        String lastName,
        String email,
        String photoUrl,
        String emergencyContactName,
        String emergencyContactPhone
) {
}
