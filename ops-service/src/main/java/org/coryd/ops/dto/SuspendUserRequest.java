package org.coryd.ops.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Why - kept on the account and shown to whoever reinstates it. */
public record SuspendUserRequest(@NotBlank @Size(max = 500) String reason) {
}
