package org.coryd.common.security;

import lombok.Getter;
import org.coryd.common.enums.UserRole;
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
    private final String fullName;
    private final UserRole activeRole;

    public CoRydUserDetails(String userId, String phone, String fullName, UserRole activeRole) {
        this.userId = userId;
        this.phone = phone;
        this.fullName = fullName;
        this.activeRole = activeRole;
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
