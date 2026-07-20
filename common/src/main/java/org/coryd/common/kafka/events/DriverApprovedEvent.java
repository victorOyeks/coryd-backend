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
public class DriverApprovedEvent {
    private String driverId;
    private String vehicleId;
    private String reviewedBy;
    private LocalDateTime approvedAt;
}
