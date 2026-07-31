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
public class SosTriggeredEvent {
    private String incidentId;
    private String bookingId;
    private String triggeredBy;
    private Double latitude;
    private Double longitude;
    private LocalDateTime triggeredAt;
    /** Null if the rider never set an emergency contact - notification-service skips the SMS in that case. */
    private String emergencyContactName;
    private String emergencyContactPhone;
}
