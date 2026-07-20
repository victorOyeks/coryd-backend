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

import java.time.LocalDate;

@Entity
@Table(name = "driver_documents", schema = "profile")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class DriverDocument extends BaseEntity {

    @Column(nullable = false)
    private String driverId;

    @Column(nullable = false, length = 30)
    private String documentType; // DRIVERS_LICENSE, ROADWORTHINESS_CERT, INSURANCE

    @Column(nullable = false)
    private String documentUrl;

    private LocalDate expiresOn;

    @Column(name = "reminder_sent_14d")
    private boolean reminderSent14d;

    @Column(name = "reminder_sent_3d")
    private boolean reminderSent3d;
}
