package org.coryd.common.kafka.events;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** The Ops Console's live badges, as trip-service counts them. Sent when they change (and every few minutes regardless). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OpsCountsChangedEvent {
    private long sosOpen;
    private long sosAcknowledged;
    private long sosEscalated;
    private long stuckTrips;
    private long reportsOpen;
    private long reportsInProgress;
    private Instant computedAt;
}
