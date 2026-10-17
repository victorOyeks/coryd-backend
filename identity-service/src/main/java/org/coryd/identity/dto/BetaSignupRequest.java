package org.coryd.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.coryd.identity.entity.BetaSignup;

/** The website's "Join the CoRyd beta" form. */
public record BetaSignupRequest(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Size(max = 20) String whatsappNumber,
        @NotNull BetaSignup.Intent willMostly,
        @NotBlank @Size(max = 200) String usualRoute,
        @NotNull BetaSignup.Device device
) {
}
