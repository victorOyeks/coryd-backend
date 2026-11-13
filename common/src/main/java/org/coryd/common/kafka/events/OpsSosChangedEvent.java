package org.coryd.common.kafka.events;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * An SOS-queue incident changed: RAISED (only for an unanswered safety check - a pressed SOS is SOS_TRIGGERED),
 * ACKNOWLEDGED, NOTE, ESCALATED, RESOLVED or REALERTED. Read by ops-service's live stream.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OpsSosChangedEvent {
    private String incidentId;
    /** SOS or SAFETY_CHECK. */
    private String kind;
    private String action;
    /** SosStatus after the change. */
    private String status;
    private String routePostId;
    private String bookingId;
    private String safetyAlertId;
    /** The admin, or null for the system (RAISED, REALERTED). */
    private String actorId;
    private String actorName;
    /** RESOLVED only. */
    private String outcome;
    /** REALERTED only: minutes without acknowledgement. */
    private Integer minutesOpen;
    private Instant at;
}
