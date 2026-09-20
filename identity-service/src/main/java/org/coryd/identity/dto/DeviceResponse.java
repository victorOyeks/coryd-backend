package org.coryd.identity.dto;

import org.coryd.common.enums.DevicePlatform;

import java.time.Instant;

/**
 * A device on the user's "where you're signed in" screen.
 *
 * @param current true for the device that made this request, so the app can label it "This device"
 *                and warn before signing itself out.
 */
public record DeviceResponse(
        String deviceId,
        String deviceName,
        DevicePlatform platform,
        boolean biometricEnabled,
        Instant lastSeenAt,
        boolean current
) {
}
