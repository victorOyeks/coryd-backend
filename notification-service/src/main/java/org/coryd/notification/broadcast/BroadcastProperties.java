package org.coryd.notification.broadcast;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Broadcast sending rules. Quiet hours (Lagos time, default 21:00-07:00): a broadcast that isn't urgent never goes out
 * inside them - one scheduled in them is moved to their end, one still sending when they start pauses until they end.
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "broadcast")
public class BroadcastProperties {

    public static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");

    /** Recipients sent per second, across every channel - keeps Termii, FCM and SMTP under their limits. */
    private int ratePerSecond = 10;

    private LocalTime quietHoursStart = LocalTime.of(21, 0);
    private LocalTime quietHoursEnd = LocalTime.of(7, 0);

    /** Broadcasts that can be created in any hour, by all admins together - a mis-click can't flood everyone. */
    private int maxPerHour = 10;

    public boolean isQuiet(Instant at) {
        LocalTime t = at.atZone(LAGOS).toLocalTime();
        if (quietHoursStart.equals(quietHoursEnd)) {
            return false;
        }
        return quietHoursStart.isBefore(quietHoursEnd)
                ? !t.isBefore(quietHoursStart) && t.isBefore(quietHoursEnd)
                : !t.isBefore(quietHoursStart) || t.isBefore(quietHoursEnd);
    }

    /** The first moment at or after at that is outside quiet hours. */
    public Instant nextAllowed(Instant at) {
        if (!isQuiet(at)) {
            return at;
        }
        ZonedDateTime z = at.atZone(LAGOS);
        ZonedDateTime end = z.toLocalDate().atTime(quietHoursEnd).atZone(LAGOS);
        if (!end.isAfter(z)) {
            end = end.plusDays(1);
        }
        return end.toInstant();
    }
}
