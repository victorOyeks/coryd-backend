package org.coryd.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SuspendRequest(@NotBlank @Size(max = 500) String reason, String opsUserId) {
}
