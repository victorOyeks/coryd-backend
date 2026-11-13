package org.coryd.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * An ops action on an account that takes a reason - suspend, reinstate, sign out everywhere. opsUserId / opsUserName are
 * the admin, set by ops-service from their token.
 */
public record AccountActionRequest(@NotBlank(message = "is required") @Size(max = 500) String reason,
                                   @Size(max = 36) String opsUserId,
                                   @Size(max = 150) String opsUserName) {
}
