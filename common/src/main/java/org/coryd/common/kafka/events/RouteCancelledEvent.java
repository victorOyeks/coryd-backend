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
public class RouteCancelledEvent {
    private String routePostId;
    private String driverId;
    private List<String> affectedBookingIds;
    private LocalDateTime cancelledAt;
}
