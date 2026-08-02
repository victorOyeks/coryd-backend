package org.coryd.profile.dto;

import jakarta.validation.constraints.NotBlank;

public record AddEmergencyContactRequest(@NotBlank String name, @NotBlank String phone) {
}
