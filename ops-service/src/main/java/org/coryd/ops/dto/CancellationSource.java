package org.coryd.ops.dto;

/** Who or what ended a trip or a seat - the Cancellations Log's tabs. */
public enum CancellationSource {
    RIDER,
    DRIVER,
    SYSTEM_EXPIRY,
    SYSTEM,
    OPS,
    SUSPENSION,
    ACCOUNT_DELETED
}
