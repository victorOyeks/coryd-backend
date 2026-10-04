package org.coryd.payment.dto;

/** Paystack's settlement states. SUCCESS means the money reached the driver's bank. */
public enum SettlementStatus {
    PENDING,
    PROCESSING,
    SUCCESS,
    FAILED;

    public static SettlementStatus fromGateway(String status) {
        try {
            return status == null ? PENDING : valueOf(status.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return PENDING;
        }
    }
}
