package org.coryd.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.coryd.identity.entity.BetaSignup;

/**
 * The website's "Join the CoRyd beta" form. website is a honeypot: the form hides it, so only a bot
 * fills it in - such a submission is answered like any other and dropped.
 */
public record BetaSignupRequest(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Size(max = 20) String whatsappNumber,
        @NotNull BetaSignup.Intent willMostly,
        @NotBlank @Size(max = 200) String usualRoute,
        @NotNull BetaSignup.Device device,
        String website
) {
}
