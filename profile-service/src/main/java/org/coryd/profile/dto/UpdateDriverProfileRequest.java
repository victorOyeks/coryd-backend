package org.coryd.profile.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

import java.time.LocalDate;

public record UpdateDriverProfileRequest(
        @NotBlank String firstName,
        @NotBlank String lastName,
        String photoUrl,
        @Email String email,
        LocalDate dateOfBirth,
        String governmentIdUrl,
        String idNumber
) {
}
