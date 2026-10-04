package org.coryd.payment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.coryd.common.entity.BaseEntity;

/** A rider's reusable card from an earlier Paystack checkout. authorizationCode is what charges it
 * again and is never sent to a client; Paystack only accepts it alongside the email it was issued to. */
@Entity
@Table(name = "saved_cards", schema = "payment")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class SavedCard extends BaseEntity {

    @Column(nullable = false, length = 36)
    private String riderId;

    @Column(nullable = false, length = 100)
    private String fingerprint;

    @Column(nullable = false, length = 100)
    private String authorizationCode;

    @Column(nullable = false)
    private String email;

    @Column(length = 50)
    private String cardType;

    @Column(length = 4)
    private String last4;

    @Column(length = 2)
    private String expMonth;

    @Column(length = 4)
    private String expYear;

    @Column(length = 100)
    private String bank;
}
