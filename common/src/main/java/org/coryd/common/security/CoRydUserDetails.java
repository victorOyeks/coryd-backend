package org.coryd.common.security;

import lombok.Getter;
import org.coryd.common.enums.UserRole;
import org.coryd.common.util.Names;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * A JWT is scoped to one active role per session (matching marketrun-ng) even
 * though a User account can hold several roles - e.g. a Rider who also drives
 * gets one token while using the Rider app and a different one while using the
 * Driver app, rather than a single token authorized for both at once.
 */
@Getter
public class CoRydUserDetails implements UserDetails {

    private final String userId;
    private final String phone;
    private final String firstName;
    private final String lastName;
    private final UserRole activeRole;
    /**
     * The ops admin's console role exactly as the token states it (OpsRole.CLAIM) - null when the token has none (not an
     * ops admin, or a token from before ops roles). Kept raw so an unrecognised value can be refused rather than guessed;
     * ops-service turns it into permissions.
     */
    private final String opsRole;
    /** When the token was issued, epoch millis (iat_ms, else iat) - 0 for a principal built by hand. */
    private final long issuedAtMs;
    /** Ops console sessions: when the session ends at the latest (JwtUtil.SESSION_EXPIRES_AT_CLAIM); null otherwise. */
    private final java.time.Instant sessionExpiresAt;

    public CoRydUserDetails(String userId, String phone, String firstName, String lastName, UserRole activeRole) {
        this(userId, phone, firstName, lastName, activeRole, null);
    }

    public CoRydUserDetails(String userId, String phone, String firstName, String lastName, UserRole activeRole, String opsRole) {
        this(userId, phone, firstName, lastName, activeRole, opsRole, 0L, null);
    }

    public CoRydUserDetails(String userId, String phone, String firstName, String lastName, UserRole activeRole, String opsRole,
                            long issuedAtMs, java.time.Instant sessionExpiresAt) {
        this.issuedAtMs = issuedAtMs;
        this.sessionExpiresAt = sessionExpiresAt;
        this.userId = userId;
        this.phone = phone;
        this.firstName = firstName;
        this.lastName = lastName;
        this.activeRole = activeRole;
        this.opsRole = opsRole;
    }

    /** Derived, not stored - the token carries the two parts. */
    public String getFullName() {
        return Names.join(firstName, lastName);
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + activeRole.name()));
    }

    @Override
    public String getPassword() {
        return null;
    }

    @Override
    public String getUsername() {
        return phone;
    }
}
