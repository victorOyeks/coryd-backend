package org.coryd.common.kafka.events;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** What a driver owes from refunded trips went up (REFUNDED_TRIP) or down (PAID_BY_CARD). change is signed. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DriverOwedChangedEvent {
    private String driverId;
    private String reason;
    private BigDecimal change;
    private BigDecimal amountOwed;
    private BigDecimal owedLimit;
    private boolean overLimit;
    private String bookingId;
    private LocalDateTime changedAt;
}
