package org.coryd.common.kafka.events;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TripSafetyCheckEvent {
    private String alertId;
    private String routePostId;
    /** STOPPED, OFF_ROUTE or SIGNAL_LOST. */
    private String kind;
    /** The driver and every rider on board - each is pushed "Are you OK?". */
    private List<String> userIds;
}
