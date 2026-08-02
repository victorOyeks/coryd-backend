package org.coryd.common.kafka.events;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DriverEnRouteEvent {
    private String bookingId;
    private String riderId;
    private String driverName;
    private LocalDateTime occurredAt;
}
