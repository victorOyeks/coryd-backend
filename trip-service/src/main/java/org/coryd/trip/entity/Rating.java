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
import org.coryd.common.enums.RatingDirection;


@Entity
@Table(name = "ratings", schema = "trip")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class Rating extends BaseEntity {

    @Column(nullable = false)
    private String bookingId;

    @Column(nullable = false)
    private String raterId;

    @Column(nullable = false)
    private String rateeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RatingDirection direction;

    @Column(nullable = false)
    private Integer stars;

    private String comment;
}
