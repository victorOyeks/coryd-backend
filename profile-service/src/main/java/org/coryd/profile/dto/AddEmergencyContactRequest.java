package org.coryd.profile.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AddEmergencyContactRequest(@NotBlank @Size(max = 100) String name, @NotBlank @Size(max = 20) String phone) {
}
