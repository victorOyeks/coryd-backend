package org.coryd.profile.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.coryd.common.entity.BaseEntity;


@Entity
@Table(name = "rider_profiles", schema = "profile")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class RiderProfile extends BaseEntity {

    @Column(nullable = false, unique = true)
    private String userId;

    @Column(length = 100)
    private String fullName;

    private String email;

    private String photoUrl;

    private String emergencyContactName;

    @Column(length = 20)
    private String emergencyContactPhone;
}
