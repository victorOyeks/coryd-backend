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
}
