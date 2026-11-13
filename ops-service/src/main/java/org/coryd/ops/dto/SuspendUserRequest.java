package org.coryd.ops.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Why - suspend, reinstate, sign out everywhere. Kept in the account history; a suspension's reason is also shown on the profile. */
public record SuspendUserRequest(@NotBlank(message = "is required") @Size(max = 500) String reason) {
}
