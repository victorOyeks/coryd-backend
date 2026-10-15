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
    /** Null when a driver raised it on the route itself. */
    private String bookingId;
    private String routePostId;
    private String triggeredBy;
    /** RIDER or DRIVER - which side of the trip pressed it. */
    private String triggeredByRole;
    /** For the ops alert - who to call on both sides, and which car. Any of these may be null if a lookup failed. */
    private String riderPhone;
    private String driverPhone;
    private String driverName;
    private String vehiclePlate;
    private String routeSummary;
    private Double latitude;
    private Double longitude;
    private LocalDateTime triggeredAt;
    /** Empty if the rider never set an emergency contact - notification-service skips the SMS in that case. Every contact gets one, not just the primary. */
    private List<EmergencyContactPayload> contacts;
}
