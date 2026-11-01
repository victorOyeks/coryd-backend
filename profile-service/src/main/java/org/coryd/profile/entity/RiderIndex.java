package org.coryd.profile.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.coryd.common.enums.RiderDirectoryStatus;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One row per rider with everything the Riders Directory filters, sorts and searches on - one indexed query however many
 * riders there are. Rebuilt from the rider's profile whenever it changes (RiderIndexListener); trip numbers and
 * suspensions arrive as events. Never the source of truth - RiderIndexService can rebuild any row.
 */
@Entity
@Table(name = "rider_index", schema = "profile")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiderIndex {

    @Id
    @Column(length = 36)
    private String riderId;

    /** The id without dashes, lower case - the "R-4F2A91" reference searches and sorts on this. */
    @Column(nullable = false, length = 36)
    private String refKey;

    private String firstName;
    private String lastName;
    /** "first last", lower case - name search and sort. */
    private String nameKey;
    private String photoUrl;
    private String phone;
    /** The phone's digits only - phone search. */
    private String phoneDigits;
    private Instant joinedAt;

    private Instant suspendedAt;
    private String suspensionReason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RiderDirectoryStatus directoryStatus;

    private Long trips;
    private Double ratingAvg;
    private Long ratingCount;
    private Integer reliabilityPercent;
    private Long reportsFiled;
    private BigDecimal totalSpent;
    /** When trip-service worked those numbers out - an older message is ignored. */
    private Instant statsAt;

    private Instant refreshedAt;
}
