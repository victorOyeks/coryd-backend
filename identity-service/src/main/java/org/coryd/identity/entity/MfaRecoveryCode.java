package org.coryd.identity.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.coryd.common.entity.BaseEntity;

import java.time.Instant;

/** One of an ops admin's one-time recovery codes - only its SHA-256 is kept. */
@Entity
@Table(name = "mfa_recovery_codes", schema = "identity")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class MfaRecoveryCode extends BaseEntity {

    @Column(nullable = false, length = 36)
    private String userId;

    @Column(nullable = false, length = 64)
    private String codeHash;

    private Instant usedAt;
}
