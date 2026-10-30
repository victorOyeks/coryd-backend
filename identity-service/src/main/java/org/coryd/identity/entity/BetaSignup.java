package org.coryd.identity.entity;

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

import java.time.Instant;

/**
 * Someone who joined the beta waitlist from the website, before having an account. One per WhatsApp
 * number - submitting again updates it. joinedAt is set when they later sign up in the app with it.
 */
@Entity
@Table(name = "beta_signups", schema = "identity")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class BetaSignup extends BaseEntity {

    public enum Intent { RIDE, DRIVE }

    public enum Device { IPHONE, ANDROID }

    public enum Status { WAITING, INVITED }

    @Column(nullable = false, length = 100)
    private String name;

    /** Normalised local form (0803...), as accounts store it. */
    @Column(nullable = false, unique = true, length = 20)
    private String whatsappNumber;

    /** Lower-cased. What ops adds to TestFlight / Play testing - null only on sign-ups from before it was asked. */
    @Column(length = 254)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Intent willMostly;

    @Column(nullable = false, length = 200)
    private String usualRoute;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Device device;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private Status status = Status.WAITING;

    private Instant invitedAt;

    @Column(length = 36)
    private String invitedBy;

    /** When they created an account in the app with this number. */
    private Instant joinedAt;
}
