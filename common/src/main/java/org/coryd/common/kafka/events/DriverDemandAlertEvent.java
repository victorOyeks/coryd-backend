package org.coryd.common.kafka.events;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

/** The evening push to a driver: riders want routes they drive, tomorrow (forDate), and no one has posted them. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DriverDemandAlertEvent {
    private String driverId;
    private LocalDate forDate;
    private List<DemandRoute> routes;

    /** windowStart/windowEnd: the earliest and latest times riders want to leave, "HH:mm" Lagos time. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DemandRoute {
        private String originLabel;
        private String destinationLabel;
        private String windowStart;
        private String windowEnd;
        private int riderCount;
    }
}
