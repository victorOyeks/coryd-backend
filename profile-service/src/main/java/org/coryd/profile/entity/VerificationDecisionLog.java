package org.coryd.profile.entity;

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
import org.coryd.common.enums.ReviewableItem;
import org.coryd.common.enums.VerificationStatus;

/** Every decision on a driver's item, by whom and why - the review screen's audit log. Never edited. */
@Entity
@Table(name = "verification_decision_log", schema = "profile")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class VerificationDecisionLog extends BaseEntity {

    @Column(nullable = false, length = 36)
    private String driverId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ReviewableItem item;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private VerificationStatus status;

    /** The rejection reason the driver was shown, or the reviewer's note on an approval. */
    @Column(length = 500)
    private String note;

    @Column(nullable = false, length = 150)
    private String decidedBy;
}
