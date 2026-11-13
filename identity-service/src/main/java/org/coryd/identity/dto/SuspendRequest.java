package org.coryd.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** opsUserId / opsUserName: the admin, set by ops-service from their token. */
public record SuspendRequest(@NotBlank @Size(max = 500) String reason, @Size(max = 36) String opsUserId, @Size(max = 150) String opsUserName) {
}
