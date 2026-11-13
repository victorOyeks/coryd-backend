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
        String email
) {
}
