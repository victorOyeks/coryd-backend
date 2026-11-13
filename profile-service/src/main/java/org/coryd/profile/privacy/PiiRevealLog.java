package org.coryd.profile.privacy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.coryd.common.entity.BaseEntity;
import org.coryd.common.enums.PiiField;

/**
 * One deliberate reveal of a person's full NIN, licence number or date of birth by an ops admin - who, whose, which and
 * why (NDPA 2023, docs/ops-console/privacy.md). Shown in the unified audit log as PII_REVEALED. Never edited; the value
 * itself is never stored here.
 */
@Entity
@Table(name = "pii_reveal_log", schema = "profile")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class PiiRevealLog extends BaseEntity {

    /** The person whose data was shown. */
    @Column(nullable = false, length = 36)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PiiField field;

    @Column(nullable = false, length = 36)
    private String actorId;

    @Column(length = 120)
    private String actorName;

    @Column(nullable = false, length = 500)
    private String reason;

    /** What the console showed before the reveal ("•••• 5040") - never the full value. */
    @Column(length = 40)
    private String maskedValue;
}
