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
    /** The paid bookings this cancellation ended - each is refunded. */
    private List<String> affectedBookingIds;
    private String cancelledBy;
    private LocalDateTime cancelledAt;
}
