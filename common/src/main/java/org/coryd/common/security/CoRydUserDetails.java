package org.coryd.common.security;

import lombok.Getter;
import org.coryd.common.enums.UserRole;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

@Getter
public class CoRydUserDetails implements UserDetails {

    private final String userId;
    private final String phone;
    private final String fullName;
    private final Set<UserRole> roles;

    public CoRydUserDetails(String userId, String phone, String fullName, Set<UserRole> roles) {
        this.userId = userId;
        this.phone = phone;
        this.fullName = fullName;
        this.roles = roles;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return roles.stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.name()))
                .collect(Collectors.toSet());
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
