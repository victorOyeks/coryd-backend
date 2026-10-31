package org.coryd.trip.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** A driver changing their trip's time instead of cancelling it - the limits once riders have booked. */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "trip.time-change")
public class TimeChangeProperties {

    /** With riders booked, the trip can end up at most this much later than the time they booked. */
    private int maxMinutes = 120;

    /** With riders booked, at most this many changes - after that, cancel and post again. */
    private int maxCount = 3;

    /** A rider whose trip has moved more than this since they booked can cancel free, with a full refund. */
    private int freeCancelMinutes = 15;

    /** A change this close to departure, with riders booked, counts against the driver as last-minute. */
    private int lastMinuteMinutes = 60;
}
