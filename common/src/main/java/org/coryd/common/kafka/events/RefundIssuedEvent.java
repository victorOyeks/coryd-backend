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
    private String bookingId;
    private String riderId;
    private BigDecimal amount;
    private String reason;
    private LocalDateTime refundedAt;
}
