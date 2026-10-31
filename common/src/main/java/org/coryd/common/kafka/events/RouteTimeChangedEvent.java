package org.coryd.common.kafka.events;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/** A driver moved their trip's departure before starting it - every paid rider on it is told. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RouteTimeChangedEvent {
    private String routePostId;
    private String driverId;
    private String originLabel;
    private String destinationLabel;
    private Instant previousDepartureTime;
    private Instant departureTime;
    private List<RiderBooking> riders;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RiderBooking {
        private String riderId;
        private String bookingId;
        /** The trip has moved far enough from what they booked that cancelling is free. */
        private boolean freeCancellation;
    }
}
