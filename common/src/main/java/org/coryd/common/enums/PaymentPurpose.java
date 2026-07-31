package org.coryd.common.enums;

public enum PaymentPurpose {
    BOOKING_PAYMENT,
    WALLET_TOPUP,
    /** Wallet-transaction-only purpose - a cancelled booking's fare credited back to the rider's wallet. */
    REFUND
}
