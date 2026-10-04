package org.coryd.common.enums;

public enum PaymentMethod {
    /** No longer accepted - kept so an older app sending it gets a clear "pay by card" answer instead of a parse error. */
    WALLET,
    PAYSTACK
}
