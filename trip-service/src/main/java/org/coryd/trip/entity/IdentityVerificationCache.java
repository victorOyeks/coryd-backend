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

/** Local copy of profile-service's identity (NIN + selfie) decision per person, so booking and posting needn't call it. */
@Entity
@Table(name = "identity_verification_cache", schema = "trip")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class IdentityVerificationCache {

    @Id
    private String userId;

    @Column(nullable = false)
    private boolean verified;

    private Instant decidedAt;
}
