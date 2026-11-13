package org.coryd.trip.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/** How long an SOS can sit unacknowledged before ops is alerted again - once per threshold. */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "trip.sos")
public class SosOpsProperties {

    /** Minutes after it was raised; each one re-alerts once if nobody has acknowledged it. */
    private List<Integer> realertMinutes = new ArrayList<>(List.of(5, 15, 30));

    /** An SOS older than this is no longer re-alerted - it still waits in the queue. */
    private int realertMaxAgeHours = 12;

    public List<Integer> thresholds() {
        return realertMinutes == null ? List.of() : realertMinutes.stream().filter(m -> m != null && m > 0).sorted().distinct().toList();
    }
}
