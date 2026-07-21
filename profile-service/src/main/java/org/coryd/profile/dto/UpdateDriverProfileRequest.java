package org.coryd.profile.dto;

import jakarta.validation.constraints.NotBlank;

public record UpdateDriverProfileRequest(
        @NotBlank String legalName,
        String photoUrl,
        String governmentIdUrl
) {
}
