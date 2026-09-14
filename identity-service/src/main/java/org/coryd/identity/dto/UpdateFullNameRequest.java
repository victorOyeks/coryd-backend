package org.coryd.identity.dto;

import jakarta.validation.constraints.NotBlank;

public record UpdateFullNameRequest(@NotBlank String fullName) {
}
