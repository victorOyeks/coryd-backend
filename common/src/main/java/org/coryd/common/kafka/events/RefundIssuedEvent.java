package org.coryd.common.kafka.events;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RefundIssuedEvent {

    /** The booking was cancelled and this is its refund. Also how an event without a cause is read. */
    public static final String CAUSE_CANCELLATION = "CANCELLATION";
    /** CoRyd support refunded some or all of the fare by hand - the booking may well not be cancelled (M50). */
    public static final String CAUSE_OPS_REFUND = "OPS_REFUND";

    private String bookingId;
    private String riderId;
    private BigDecimal amount;
    private String reason;
    private LocalDateTime refundedAt;
    /** CAUSE_CANCELLATION or CAUSE_OPS_REFUND - null from a sender that predates it, read as a cancellation. */
    private String cause;
    /** The trip's route, for the rider's push - null when payment-service couldn't look it up. */
    private String originLabel;
    private String destinationLabel;

    public boolean isOpsRefund() {
        return CAUSE_OPS_REFUND.equals(cause);
    }
}
