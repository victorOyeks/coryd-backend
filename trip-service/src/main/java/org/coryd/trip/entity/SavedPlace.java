package org.coryd.trip.entity;

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
import org.coryd.common.enums.SavedPlaceType;

@Entity
@Table(name = "saved_places", schema = "trip")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class SavedPlace extends BaseEntity {

    @Column(nullable = false)
    private String riderId;

    @Column(nullable = false, length = 100)
    private String label;

    @Column(nullable = false, length = 255)
    private String formattedAddress;

    @Column(nullable = false)
    private Double lat;

    @Column(nullable = false)
    private Double lng;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(nullable = false, length = 20)
    private SavedPlaceType type = SavedPlaceType.GENERAL;
}
