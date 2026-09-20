package org.coryd.identity.dto;

import jakarta.validation.constraints.NotBlank;

public record UpdateNameRequest(@NotBlank String firstName, String lastName) {
}
