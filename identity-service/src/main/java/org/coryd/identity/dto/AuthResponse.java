package org.coryd.identity.dto;

import java.util.Set;

public record AuthResponse(
        String userId,
        String phone,
        Set<String> roles,
        String accessToken,
        String refreshToken
) {
}
