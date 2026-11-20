package org.coryd.trip.entity;

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

import java.time.Instant;

/**
 * A person's first completed trip in one role, and what they told us about it. The row is created PENDING when the trip
 * that made them a first-timer completes (only then - people who already had trips never get one), and the app asks
 * "celebrate?" until they share (SUBMITTED) or skip (SKIPPED). One per user and role, so someone who both rides and
 * drives is celebrated once for each.
 */
@Entity
@Table(name = "first_trip_experiences", schema = "trip")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class FirstTripExperience extends BaseEntity {

    public enum Role { DRIVER, RIDER }

    public enum Status { PENDING, SUBMITTED, SKIPPED }

    /** The five faces, best first. */
    public enum Feeling { LOVED_IT, GOOD, OKAY, NOT_GREAT, BAD }

    @Column(nullable = false, length = 36)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Role role;

    @Column(nullable = false, length = 36)
    private String routePostId;

    /** The rider's booking; null for a driver. */
    @Column(length = 36)
    private String bookingId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status;

    @Enumerated(EnumType.STRING)
    @Column(length = 12)
    private Feeling feeling;

    @Column(length = 500)
    private String story;

    @Column(nullable = false)
    private boolean consentToShare;

    /** When the trip completed. */
    @Column(nullable = false)
    private Instant completedAt;

    /** When they shared or skipped. */
    private Instant respondedAt;
}
