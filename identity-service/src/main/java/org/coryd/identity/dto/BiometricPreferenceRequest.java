package org.coryd.identity.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Records that the user has turned fingerprint sign-in on or off for one device.
 *
 * <p>This is bookkeeping for the devices screen, not an authentication step. The backend cannot
 * check a fingerprint and never sees one; enabling it here does not grant anything, and a client
 * that skipped the call is not prevented from using biometrics locally. The value exists so the
 * user can see, from another phone, which of their devices they left fingerprint sign-in on.
 */
public record BiometricPreferenceRequest(
        @NotBlank String deviceId,
        boolean enabled
) {
}
