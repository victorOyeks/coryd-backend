package org.coryd.common.kafka.events;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

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
    /** Empty if the rider never set an emergency contact - notification-service skips the SMS in that case. Every contact gets one, not just the primary. */
    private List<EmergencyContactPayload> contacts;
}
