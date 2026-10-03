package org.coryd.profile.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.coryd.common.entity.BaseEntity;

@Entity
@Table(name = "emergency_contacts", schema = "profile")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class EmergencyContact extends BaseEntity {

    /** The person this contact belongs to - rider or driver, one set per user. */
    @Column(nullable = false)
    private String riderId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 20)
    private String phone;

    /** Exactly one contact per user is primary - the one shown on both profiles. SOS alerts every contact. */
    @Builder.Default
    @Column(name = "is_primary", nullable = false)
    private boolean primary = false;
}
