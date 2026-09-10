package org.coryd.common.kafka.events;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SeatReservedEvent {
    private String bookingId;
    private String routePostId;
    private String riderId;
    private String driverId;
    private String driverName;
    private Integer seatCount;
    private BigDecimal fareTotal;
    private Instant holdExpiresAt;
}
