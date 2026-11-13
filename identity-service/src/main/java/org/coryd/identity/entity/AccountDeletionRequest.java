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
import org.coryd.common.enums.AccountDeletionStatus;

import java.time.Instant;

/**
 * An account deletion, asked for by its owner (requestedBy SELF) or by ops. PENDING while the last try was refused -
 * blockers says why; COMPLETED once the account is gone. Keeps only what identifies the request after the account is erased.
 */
@Entity
@Table(name = "account_deletion_requests", schema = "identity")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class AccountDeletionRequest extends BaseEntity {

    @Column(nullable = false, length = 36)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountDeletionStatus status;

    /** SELF, or the ops admin's user id. */
    @Column(nullable = false, length = 36)
    private String requestedBy;

    /** Comma-separated roles the account held. */
    @Column(length = 100)
    private String roles;

    @Column(length = 4)
    private String phoneLast4;

    private Instant joinedAt;

    /** JSON array of the reasons the last try was refused; null once completed. */
    @Column(columnDefinition = "TEXT")
    private String blockers;

    @Builder.Default
    @Column(nullable = false)
    private int attempts = 1;

    @Column(nullable = false)
    private Instant lastAttemptAt;

    private Instant completedAt;

    /** SELF, or the ops admin's user id. */
    @Column(length = 36)
    private String completedBy;
}
