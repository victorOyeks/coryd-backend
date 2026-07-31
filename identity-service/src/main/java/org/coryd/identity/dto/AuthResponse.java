package org.coryd.identity.dto;

import java.util.Set;

public record AuthResponse(
        String userId,
        String fullName,
        String phone,
        String activeRole,
        Set<String> roles,
        String accessToken,
        long expiresIn,
        String refreshToken,
        long refreshExpiresIn
) {
}
