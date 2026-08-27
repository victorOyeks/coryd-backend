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

@Entity
@Table(name = "suggested_fare_ranges", schema = "trip")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class SuggestedFareRange extends BaseEntity {

    @Column(nullable = false, unique = true, length = 512)
    private String corridorKey;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal minFare;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal maxFare;

    @Column(nullable = false)
    private Integer sampleSize;
}
