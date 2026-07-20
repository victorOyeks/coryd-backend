package org.coryd.common.kafka.events;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoutePublishedEvent {
    private String routePostId;
    private String driverId;
    private String originLabel;
    private String destinationLabel;
    private LocalDateTime departureTime;
    private int seatsAvailable;
}
