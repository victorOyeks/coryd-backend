package org.coryd.identity.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import org.coryd.common.entity.BaseEntity;
import org.coryd.common.enums.UserRole;

import java.time.Instant;

@Entity
@Table(name = "refresh_tokens", schema = "identity")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class RefreshToken extends BaseEntity {

    @Column(nullable = false)
    private String userId;

    @Column(nullable = false, unique = true, length = 512)
    private String token;

    /** Which role context this session was issued for, so refresh preserves it instead of silently switching apps. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private UserRole activeRole;

    @Column(nullable = false)
    private Instant expiresAt;

    @Builder.Default
    @Column(nullable = false)
    private boolean revoked = false;
}
