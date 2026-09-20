package org.coryd.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param deviceId must match the device the token was issued to, when it was issued to one.
 *                 Omitting it against a bound token is rejected - otherwise binding would be
 *                 trivially bypassed by simply leaving the field out.
 */
public record RefreshTokenRequest(
        @NotBlank String refreshToken,
        @Size(max = 100) String deviceId
) {
}
