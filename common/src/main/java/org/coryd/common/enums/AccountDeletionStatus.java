package org.coryd.common.enums;

/** An account deletion request: PENDING while the last try was refused (a trip under way, money still moving), COMPLETED once the account is gone. */
public enum AccountDeletionStatus {
    PENDING,
    COMPLETED
}
