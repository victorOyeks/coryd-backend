package org.coryd.profile.dto;

import jakarta.validation.constraints.NotBlank;

public record UpdateRiderProfileRequest(
        @NotBlank String fullName,
        String email,
        String photoUrl
) {
}
