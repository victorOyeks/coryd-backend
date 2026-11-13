package org.coryd.notification.broadcast;

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

import java.time.Instant;

/**
 * One person a broadcast goes to, and what happened on each channel: SENT, FAILED, or why it wasn't tried - NO_DEVICE,
 * NO_PHONE, NO_EMAIL, OPTED_OUT (WhatsApp turned off), UNAVAILABLE (no WhatsApp template). SENT means the provider accepted
 * it, not that it arrived (PRD §18).
 */
@Entity
@Table(name = "broadcast_recipients", schema = "notification")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class BroadcastRecipient extends BaseEntity {

    /** SENT: at least one channel accepted it. PARTIAL: some did, some failed. SKIPPED: not sent at all (skipReason). */
    public enum Status { PENDING, SENT, PARTIAL, FAILED, SKIPPED }

    @Column(nullable = false, length = 36)
    private String broadcastId;

    @Column(nullable = false, length = 36)
    private String userId;

    @Column(length = 10)
    private String role;

    @Column(length = 12)
    private String ref;

    @Column(length = 30)
    private String phone;

    @Column(length = 255)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status;

    @Column(length = 120)
    private String skipReason;

    @Column(length = 15)
    private String push;

    @Column(length = 15)
    private String sms;

    @Column(name = "email_outcome", length = 15)
    private String emailOutcome;

    @Column(length = 15)
    private String whatsapp;

    @Column(length = 300)
    private String error;

    private Instant sentAt;
}
