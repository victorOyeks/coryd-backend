package org.coryd.payment.dto;

public enum RefundKind {
    /** A cancelled booking - in full, or 75% for a late rider cancellation. */
    CANCELLATION,
    /** The same booking was paid twice; the extra charge went back. */
    DUPLICATE_CHARGE,
    /** A driver paid back more than they owed. */
    OVERPAYMENT
}
