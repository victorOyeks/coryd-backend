package org.coryd.common.kafka.events;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** A driver posted a ride a rider asked to be told about - or, with seatOpened, a ride we told them about
 * filled up and now has a seat again. Labels can be null on older routes. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotifyMeMatchedEvent {
    private String notifyMeRequestId;
    private String riderId;
    private String routePostId;
    private String originLabel;
    private String destinationLabel;
    private Instant departureTime;
    private boolean seatOpened;
}
