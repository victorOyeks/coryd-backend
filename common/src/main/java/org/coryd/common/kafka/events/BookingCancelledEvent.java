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
    private BigDecimal feeAmount;
    private LocalDateTime cancelledAt;
}
