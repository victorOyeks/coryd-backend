package org.coryd.profile.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.coryd.common.entity.BaseEntity;
import org.coryd.common.enums.VerificationCheckOutcome;
import org.coryd.common.enums.VerificationCheckType;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The outcome of one call to an identity provider, kept as the audit trail behind an automatic
 * approval and as the cache that stops us paying for the same lookup twice. subjectKey is the
 * licence number or plate the check was run against, so changing either is what triggers a
 * re-check - re-saving an unchanged profile does not.
 */
@Entity
@Table(name = "verification_checks", schema = "profile",
        uniqueConstraints = @UniqueConstraint(columnNames = {"driver_id", "check_type", "subject_key"}))
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class VerificationCheck extends BaseEntity {

    @Column(name = "driver_id", nullable = false)
    private String driverId;

    @Enumerated(EnumType.STRING)
    @Column(name = "check_type", nullable = false, length = 30)
    private VerificationCheckType checkType;

    @Column(name = "subject_key", nullable = false, length = 50)
    private String subjectKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private VerificationCheckOutcome outcome;

    /** 0-1 for a name comparison, null for a check that never got as far as comparing anything. */
    private Double nameMatchScore;

    /** What the provider returned, in the few words ops needs to judge it. */
    @Column(length = 500)
    private String providerSummary;

    /** Prembly's own reference where it gave one, for chasing a disputed result with them. */
    @Column(length = 100)
    private String providerReference;

    /**
     * Prembly's response as received, for audit and disputes - with every image (the NIMC photo, a
     * licence photo) replaced by "[removed]". Everything else is kept verbatim, so this column holds
     * full NINs, dates of birth, phone numbers and addresses and must never be served to a client.
     * A NIN Basic check stores {"nin": ..., "liveness": ...}. Null when Prembly did not answer.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_response", columnDefinition = "jsonb")
    private String rawResponse;
}
