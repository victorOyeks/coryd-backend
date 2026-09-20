package org.coryd.identity.entity;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.coryd.common.entity.BaseEntity;
import org.coryd.common.enums.UserRole;
import org.coryd.common.util.Names;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "users", schema = "identity")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class User extends BaseEntity {

    @Column(nullable = false, unique = true, length = 20)
    private String phone;

    @Column(length = 100)
    private String firstName;

    @Column(length = 100)
    private String lastName;

    /** Derived for display and cross-service payloads - the columns stay split so a surname can be matched on its own. */
    public String getFullName() {
        return Names.join(firstName, lastName);
    }

    /** Set the moment verify-otp succeeds - lets onboarding-status distinguish "never verified" from later incomplete steps. */
    private Instant phoneVerifiedAt;

    /** Null until "Set up your password" completes - a phone-verified account isn't fully onboarded yet. */
    private String passwordHash;

    /** R-104: explicit consent timestamp, not implied by continuing. Null until accepted. */
    private Instant termsAcceptedAt;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_roles", schema = "identity", joinColumns = @JoinColumn(name = "user_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "role")
    @Builder.Default
    private Set<UserRole> roles = new HashSet<>();

    @Builder.Default
    @Column(nullable = false)
    private boolean active = true;
}
