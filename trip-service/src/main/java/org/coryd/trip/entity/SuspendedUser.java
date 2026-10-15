package org.coryd.trip.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Accounts ops has suspended, kept in sync from identity-service's UserSuspendedEvent/UserReinstatedEvent.
 * A suspended user's access token stays valid until it expires, so posting and booking check here too.
 */
@Entity
@Table(name = "suspended_users", schema = "trip")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class SuspendedUser {

    @Id
    private String userId;

    @Column(length = 500)
    private String reason;

    @Column(nullable = false)
    private Instant suspendedAt;
}
