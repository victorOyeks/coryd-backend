package org.coryd.common.kafka.events;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RouteDepartureReminderEvent {
    private String kind;
    private String routePostId;
    private String driverId;
    private String originLabel;
    private String destinationLabel;
    private Instant departureTime;
    private List<RiderBooking> riders;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RiderBooking {
        private String riderId;
        private String bookingId;
    }
}
