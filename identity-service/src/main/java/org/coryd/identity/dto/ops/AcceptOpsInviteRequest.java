package org.coryd.identity.dto.ops;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** POST /auth/ops-invite/accept - sets the password and signs the admin in. */
public record AcceptOpsInviteRequest(@NotBlank(message = "The invite link is incomplete") String token,
                                     @NotBlank(message = "Choose a password") @Size(min = 8, max = 72, message = "Use 8 to 72 characters") String password,
                                     @NotBlank(message = "Confirm your password") String confirmPassword) {
}
