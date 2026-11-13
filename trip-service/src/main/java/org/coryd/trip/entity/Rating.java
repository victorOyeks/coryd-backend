package org.coryd.trip.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
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

import java.time.Instant;


@Entity
@Table(name = "ratings", schema = "trip")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class Rating extends BaseEntity {

    /** Null only for the system-seeded starting rating every new driver/rider profile gets - every real rating is always tied to a booking. */
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

    /** Ops hid it: still on record, but in no average and shown only in the console. */
    @JsonIgnore
    private Instant hiddenAt;
    @JsonIgnore
    @Column(length = 36)
    private String hiddenBy;
    @JsonIgnore
    @Column(length = 120)
    private String hiddenByName;
    @JsonIgnore
    @Column(length = 500)
    private String hiddenReason;

    @JsonIgnore
    public boolean isHidden() {
        return hiddenAt != null;
    }
}
