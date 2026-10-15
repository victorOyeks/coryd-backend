package org.coryd.common.kafka.events;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TripSafetyEscalatedEvent {
    private String alertId;
    private String routePostId;
    private String kind;
    private String routeSummary;
    private String driverName;
    private String driverPhone;
    private String vehiclePlate;
    /** Comma-separated - every rider on board. */
    private String riderPhones;
    private Double latitude;
    private Double longitude;
    /** How many on board never answered "Are you OK?". */
    private int unanswered;
}
