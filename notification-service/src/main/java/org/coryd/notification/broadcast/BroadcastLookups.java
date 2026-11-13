package org.coryd.notification.broadcast;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/** Bulk preference and device lookups for a page of recipients - one query each, never one per person. */
@Component
@RequiredArgsConstructor
public class BroadcastLookups {

    private final NamedParameterJdbcTemplate jdbc;

    /** Those who turned announcements (promotional) off. No preferences row means opted in. */
    public Set<String> optedOut(Collection<String> userIds) {
        if (userIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.queryForList("select user_id from notification.notification_preferences"
                + " where user_id in (:ids) and promotional_opt_in = false", new MapSqlParameterSource("ids", userIds), String.class));
    }

    public Set<String> whatsappOff(Collection<String> userIds) {
        if (userIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.queryForList("select user_id from notification.notification_preferences"
                + " where user_id in (:ids) and whatsapp_opt_in = false", new MapSqlParameterSource("ids", userIds), String.class));
    }

    public Set<String> withDevice(Collection<String> userIds) {
        if (userIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.queryForList("select distinct user_id from notification.device_tokens where user_id in (:ids)",
                new MapSqlParameterSource("ids", userIds), String.class));
    }
}
