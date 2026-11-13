package org.coryd.identity.dto;

import java.util.Set;

public record AuthResponse(
        String userId,
        String fullName,
        String firstName,
        String lastName,
        String phone,
        String activeRole,
        Set<String> roles,
        String accessToken,
        long expiresIn,
        String refreshToken,
        long refreshExpiresIn,
        // ops console sessions only (null otherwise): the admin's ops role and sign-in email
        String opsRole,
        String email,
        // ops console sessions only (null otherwise): when the session ends however active it is (the absolute limit)
        java.time.Instant sessionExpiresAt
) implements LoginResult {
}
