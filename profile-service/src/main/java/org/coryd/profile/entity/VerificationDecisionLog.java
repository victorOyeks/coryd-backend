package org.coryd.profile.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.coryd.common.entity.BaseEntity;
import org.coryd.common.enums.DecisionSubject;
import org.coryd.common.enums.VerificationDecisionAction;
import org.coryd.common.enums.VerificationStatus;

/**
 * Every verification decision, by whom and why - on a driver's four items and on anyone's identity (NIN) check (item
 * IDENTITY). The review screens' audit log. Never edited.
 */
@Entity
@Table(name = "verification_decision_log", schema = "profile")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class VerificationDecisionLog extends BaseEntity {

    /** The person decided on: the driver, or for IDENTITY the rider or driver whose NIN it is. */
    @Column(nullable = false, length = 36)
    private String driverId;

    /** Null on entries written before actions were recorded. */
    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private VerificationDecisionAction action;

    /** The status just before this decision. Null on older entries. */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private VerificationStatus previousStatus;

    /** An approval that went against what Smile ID had said - providerOutcome says what that was. */
    @Builder.Default
    @Column(nullable = false)
    private boolean overrodeProvider = false;

    /** Smile ID's last word on the check at the time: an outcome (NAME_MISMATCH...) or the retry it asked for (FACE_MISMATCH...). */
    @Column(length = 40)
    private String providerOutcome;

    /** The ops admin's user id - decidedBy holds their name. Null for Smile ID and older entries. */
    @Column(length = 36)
    private String decidedById;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private DecisionSubject item;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private VerificationStatus status;

    /** The rejection reason the driver was shown, or the reviewer's note on an approval. */
    @Column(length = 500)
    private String note;

    @Column(nullable = false, length = 150)
    private String decidedBy;
}
