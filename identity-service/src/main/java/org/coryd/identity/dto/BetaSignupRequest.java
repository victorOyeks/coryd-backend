package org.coryd.identity.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.coryd.identity.entity.BetaSignup;

/** The website's "Join the CoRyd beta" form. */
public record BetaSignupRequest(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Size(max = 20) String whatsappNumber,
        /** The address we add to TestFlight / Play testing - on Android, the phone's Google account. */
        @NotBlank @Email(message = "Enter a valid email address") @Size(max = 254) String email,
        @NotNull BetaSignup.Intent willMostly,
        @NotBlank @Size(max = 200) String usualRoute,
        @NotNull BetaSignup.Device device
) {
}
