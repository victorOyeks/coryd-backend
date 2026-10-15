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

/** blockerId never sees blockedId's rides again, and the two can't share a trip - in either direction. */
@Entity
@Table(name = "user_blocks", schema = "trip")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class UserBlock extends BaseEntity {

    @Column(nullable = false, length = 36)
    private String blockerId;

    @Column(nullable = false, length = 36)
    private String blockedId;
}
