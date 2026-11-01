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
import org.coryd.common.enums.ReviewMark;
import org.coryd.common.enums.ReviewableItem;

import java.time.Instant;

/**
 * An ops reviewer's "Verified" or "Flagged" on one of a driver's items, before the decision is sent - so the marks
 * survive closing the screen and another reviewer sees them. Only counts for the upload it was made on: once the
 * driver re-uploads, itemSubmittedAt no longer matches and the mark is ignored.
 */
@Entity
@Table(name = "verification_review_marks", schema = "profile")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class VerificationReviewMark extends BaseEntity {

    @Column(nullable = false, length = 36)
    private String driverId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ReviewableItem item;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReviewMark mark;

    /** Required on a flag - it becomes the reason the driver is shown. */
    @Column(length = 500)
    private String note;

    @Column(nullable = false, length = 150)
    private String markedBy;

    /** The upload this mark was made on. */
    private Instant itemSubmittedAt;
}
