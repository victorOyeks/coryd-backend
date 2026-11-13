package org.coryd.notification.broadcast;

import lombok.RequiredArgsConstructor;
import org.coryd.common.jobs.IntegrationHealthSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * Termii (SMS, voice, WhatsApp), FCM and SMTP, from notification_log. "Delivered" there means the provider accepted the
 * message (PRD §18), so these are refusals and errors, not lost messages.
 */
@Component
@RequiredArgsConstructor
public class NotificationIntegrationHealth implements IntegrationHealthSource {

    private final JdbcTemplate jdbc;

    @Override
    public List<IntegrationHealth> integrations(Instant since) {
        return jdbc.query("""
                select channel, count(*), count(*) filter (where delivered = false), max(created_at) filter (where delivered = false),
                       (select l.error_code from notification.notification_log l where l.channel = n.channel and l.delivered = false
                          and l.created_at >= ? order by l.created_at desc limit 1)
                from notification.notification_log n where created_at >= ? group by channel order by channel
                """, (rs, i) -> new IntegrationHealth(integration(rs.getString(1)), rs.getString(1).toLowerCase(), rs.getLong(2), rs.getLong(3),
                rs.getTimestamp(4) == null ? null : rs.getTimestamp(4).toInstant(), rs.getString(5)), Timestamp.from(since), Timestamp.from(since));
    }

    static String integration(String channel) {
        return switch (channel) {
            case "PUSH" -> "FCM";
            case "EMAIL" -> "SMTP";
            case "WHATSAPP" -> "TERMII_WHATSAPP";
            default -> "TERMII";
        };
    }
}
