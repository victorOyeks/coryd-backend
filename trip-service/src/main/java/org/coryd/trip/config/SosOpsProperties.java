package org.coryd.trip.config;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.coryd.common.settings.PlatformSetting;
import org.coryd.common.settings.PlatformSettings;
import org.springframework.beans.factory.annotation.Autowired;
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

    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    @Autowired(required = false)
    private PlatformSettings platformSettings;

    /** The configured thresholds, before ops' - the default the console shows. */
    public List<Integer> configuredRealertMinutes() {
        return realertMinutes == null ? List.of() : realertMinutes;
    }

    /** Ops' runtime value (safety.sos-realert-minutes), else SOS_REALERT_MINUTES - sorted, positive, distinct. */
    public List<Integer> thresholds() {
        List<Integer> minutes = PlatformSettings.integers(platformSettings, PlatformSetting.SOS_REALERT_MINUTES, realertMinutes);
        return minutes == null ? List.of() : minutes.stream().filter(m -> m != null && m > 0).sorted().distinct().toList();
    }
}
