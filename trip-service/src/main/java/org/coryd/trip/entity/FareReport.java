package org.coryd.trip.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.coryd.common.entity.BaseEntity;

import java.math.BigDecimal;

/**
 * One row per published fare. {@code corridorKey} buckets postings on the same
 * origin/destination pair so the suggested-rate job can group comparable postings
 * (Section 10.2). Built from the normalised origin/destination labels by
 * {@code RouteService#corridorKey} - swap for a real geohash if label drift between
 * the driver's post and the rider's lookup starts fragmenting buckets.
 */
@Entity
@Table(name = "fare_reports", schema = "trip")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class FareReport extends BaseEntity {

    @Column(nullable = false, length = 512)
    private String corridorKey;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal farePerSeat;
}
