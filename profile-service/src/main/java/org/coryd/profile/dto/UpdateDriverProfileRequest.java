package org.coryd.profile.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record UpdateDriverProfileRequest(
        @NotBlank String firstName,
        @NotBlank String lastName,
        String photoUrl,
        @Email String email,
        @NotNull LocalDate dateOfBirth,
        String governmentIdUrl,
        String governmentIdPublicId,
        @NotBlank String idNumber,
        @Size(max = 100) String emergencyContactName,
        @Size(max = 20) String emergencyContactPhone
) {
}
