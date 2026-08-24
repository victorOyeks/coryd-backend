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
public class DriverRejectedEvent {
    private String driverId;
    private String vehicleId;
    private String email;
    private String rejectionReason;
    private String reviewedBy;
    private LocalDateTime rejectedAt;
}
