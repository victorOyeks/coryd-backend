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
public class BookingCancelledEvent {
    private String bookingId;
    private String routePostId;
    private String riderId;
    private String driverId;
    private String cancelledBy;
    private boolean feeApplied;
    /** Whether the rider had paid - an unpaid hold or reservation has nothing to refund. */
    private boolean paid;
    private BigDecimal feeAmount;
    private LocalDateTime cancelledAt;
    /**
     * The seat was cancelled because of the rider's account (suspended or closed), not the trip - the driver is told a seat
     * opened up, as when a rider cancels. False on older events.
     */
    private boolean riderAccountCancelled;
    /**
     * Whether the card fee the rider paid on top of the fare goes back too. True when the driver or CoRyd caused the
     * cancellation, false when the rider did (the late fee, if any, is still worked out on the fare alone). Null on older
     * events and on whole-trip cancellations: refunded unless cancelledBy is RIDER.
     */
    private Boolean refundCardFee;
    /** For the driver's "Ada cancelled their seat on ..." push when the rider cancels (M20) - null otherwise and on older events. */
    private String riderFirstName;
    private String originLabel;
    private String destinationLabel;

    /** The rule payment-service refunds by - refundCardFee when trip-service set it, else everyone but the rider. */
    public boolean cardFeeRefunded() {
        return refundCardFee != null ? refundCardFee : !"RIDER".equals(cancelledBy);
    }
}
