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

import java.time.Duration;
import java.time.Instant;

/**
 * One person's Smile ID check of one kind. The outcome itself lives where it always has - the NIN on
 * identity_verifications, the licence on driver_profiles.governmentIdStatus; this row only tracks the
 * Smile ID side: the open job, when it started, and the tries used.
 */
@Entity
@Table(name = "smile_id_checks", schema = "profile")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class SmileIdCheck extends BaseEntity {

    public static final int MAX_ATTEMPTS = 5;

    /** A check with no result after this long was abandoned (app closed mid-capture) - the person just starts again. */
    public static final Duration CHECK_WINDOW = Duration.ofMinutes(15);

    @Column(nullable = false, length = 36)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SmileIdCheckType checkType;

    /** The job the app is submitting; a result for any other job is ignored. */
    @Column(length = 50)
    private String jobId;

    /** When that job was opened - null once its result is in. */
    private Instant startedAt;

    /** Set when the last result failed in a way the person can fix by trying again. */
    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private SmileIdRetryReason retryReason;

    /** Results that said something about the person - a failed one uses a try, an outage doesn't. */
    @Builder.Default
    private int attempts = 0;

    public boolean waitingForResult() {
        return startedAt != null && startedAt.isAfter(Instant.now().minus(CHECK_WINDOW));
    }

    public int attemptsLeft() {
        return Math.max(0, MAX_ATTEMPTS - attempts);
    }
}
