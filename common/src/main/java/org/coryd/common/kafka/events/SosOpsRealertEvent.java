package org.coryd.common.kafka.events;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** An SOS-queue incident still unacknowledged after a threshold - ops is alerted again on the same channels as the first time. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SosOpsRealertEvent {
    private String incidentId;
    /** SOS or SAFETY_CHECK. */
    private String kind;
    /** RIDER, DRIVER, or SYSTEM for a safety check. */
    private String triggeredByRole;
    private String routePostId;
    private String bookingId;
    private String routeSummary;
    private String riderPhone;
    private String driverName;
    private String driverPhone;
    private String vehiclePlate;
    private Double latitude;
    private Double longitude;
    /** Minutes since it was raised. */
    private int minutesOpen;
    /** 1 for the first threshold, 2 for the second... */
    private int level;
}
