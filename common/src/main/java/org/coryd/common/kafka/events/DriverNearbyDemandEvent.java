package org.coryd.common.kafka.events;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Riders waiting near a driver who just opened the app are going the driver's way - "4 people near you are going to Ajah". */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DriverNearbyDemandEvent {
    private String driverId;
    /** The area they're going to, as most of them named it - null if none did. */
    private String destinationLabel;
    private int riderCount;
}
