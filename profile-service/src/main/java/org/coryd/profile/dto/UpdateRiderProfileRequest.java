package org.coryd.profile.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateRiderProfileRequest(
        @NotBlank String firstName,
        String lastName,
        String email,
        String photoUrl,
        @Size(max = 100) String emergencyContactName,
        @Size(max = 20) String emergencyContactPhone
) {
}
