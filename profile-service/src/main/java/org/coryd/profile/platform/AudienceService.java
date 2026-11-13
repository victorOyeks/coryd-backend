package org.coryd.profile.platform;

import lombok.RequiredArgsConstructor;
import org.coryd.common.exception.BadRequestException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Who a broadcast reaches (notification-service, docs/ops-console/broadcasts.md): riders and drivers from the directory
 * indexes, with phone and email. Suspended accounts are never in an audience. Paged by user id (keyset), so a large
 * audience is read a page at a time.
 */
@Service
@RequiredArgsConstructor
public class AudienceService {

    public static final int MAX_PAGE = 1000;
    public static final int MAX_LISTED = 5000;

    public enum Type { ALL_RIDERS, ALL_DRIVERS, VERIFIED_DRIVERS, ACTIVE_RIDERS, USERS }

    public record Member(String userId, String role, String ref, String name, String phone, String email) {
    }

    public record Page(List<Member> members, String nextAfter) {
    }

    /** unmatched: listed ids/refs that are nobody (or a suspended account). */
    public record Count(long total, long withPhone, long withEmail, List<String> unmatched) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    public Page page(Type type, Integer activeDays, List<String> ids, List<String> refs, String after, int size) {
        int limit = Math.max(1, Math.min(size, MAX_PAGE));
        MapSqlParameterSource p = params(type, activeDays, ids, refs).addValue("after", after == null ? "" : after).addValue("limit", limit + 1);
        List<Member> rows = jdbc.query("select * from (" + sql(type) + ") a where a.user_id > :after order by a.user_id limit :limit", p,
                (rs, i) -> new Member(rs.getString("user_id"), rs.getString("role"), ref(rs.getString("role"), rs.getString("ref_key")),
                        name(rs.getString("first_name"), rs.getString("last_name")), rs.getString("phone"), rs.getString("email")));
        String next = null;
        if (rows.size() > limit) {
            rows = new ArrayList<>(rows.subList(0, limit));
            next = rows.get(rows.size() - 1).userId();
        }
        return new Page(rows, next);
    }

    public Count count(Type type, Integer activeDays, List<String> ids, List<String> refs) {
        MapSqlParameterSource p = params(type, activeDays, ids, refs);
        Count counts = jdbc.queryForObject("select count(*), count(*) filter (where nullif(trim(phone), '') is not null),"
                        + " count(*) filter (where nullif(trim(email), '') is not null) from (" + sql(type) + ") a", p,
                (rs, i) -> new Count(rs.getLong(1), rs.getLong(2), rs.getLong(3), List.of()));
        if (type != Type.USERS) {
            return counts;
        }
        List<String> found = jdbc.query("select user_id, role, ref_key from (" + sql(type) + ") a", p,
                (rs, i) -> rs.getString(1) + "|" + ref(rs.getString(2), rs.getString(3)));
        List<String> unmatched = new ArrayList<>();
        for (String id : clean(ids)) {
            if (found.stream().noneMatch(f -> f.startsWith(id + "|"))) {
                unmatched.add(id);
            }
        }
        for (String r : clean(refs)) {
            if (found.stream().noneMatch(f -> f.endsWith("|" + r.toUpperCase(Locale.ROOT)))) {
                unmatched.add(r);
            }
        }
        return new Count(counts.total(), counts.withPhone(), counts.withEmail(), unmatched);
    }

    private static final String RIDERS = """
            select i.rider_id as user_id, 'RIDER' as role, i.ref_key, i.first_name, i.last_name, i.phone, p.email
            from profile.rider_index i left join profile.rider_profiles p on p.user_id = i.rider_id
            where i.directory_status <> 'SUSPENDED'
            """;
    private static final String DRIVERS = """
            select i.driver_id as user_id, 'DRIVER' as role, i.ref_key, i.first_name, i.last_name, i.phone, p.email
            from profile.driver_index i left join profile.driver_profiles p on p.user_id = i.driver_id
            where i.directory_status <> 'SUSPENDED'
            """;

    private static String sql(Type type) {
        return switch (type) {
            case ALL_RIDERS -> RIDERS;
            case ACTIVE_RIDERS -> RIDERS + " and i.last_trip_at >= :activeSince";
            case ALL_DRIVERS -> DRIVERS;
            case VERIFIED_DRIVERS -> DRIVERS + " and i.overall_status = 'APPROVED'";
            case USERS -> "select * from (" + RIDERS + " union all " + DRIVERS + ") u where u.user_id in (:ids) or "
                    + "substr(u.ref_key, 1, 6) in (:refs)";
        };
    }

    private static MapSqlParameterSource params(Type type, Integer activeDays, List<String> ids, List<String> refs) {
        if (type == null) {
            throw new BadRequestException("Choose an audience");
        }
        MapSqlParameterSource p = new MapSqlParameterSource();
        if (type == Type.ACTIVE_RIDERS) {
            if (activeDays == null || activeDays < 1 || activeDays > 365) {
                throw new BadRequestException("Riders active in the last N days needs N between 1 and 365");
            }
            p.addValue("activeSince", Timestamp.from(Instant.now().minus(Duration.ofDays(activeDays))));
        }
        if (type == Type.USERS) {
            List<String> idList = clean(ids);
            List<String> refList = clean(refs).stream().map(AudienceService::refKeyPrefix).toList();
            if (idList.isEmpty() && refList.isEmpty()) {
                throw new BadRequestException("List at least one user id or reference (R-XXXXXX / D-XXXXXX)");
            }
            if (idList.size() + refList.size() > MAX_LISTED) {
                throw new BadRequestException("A listed audience can have at most " + MAX_LISTED + " people");
            }
            p.addValue("ids", idList.isEmpty() ? List.of("-") : idList);
            p.addValue("refs", refList.isEmpty() ? List.of("-") : refList);
        }
        return p;
    }

    /** "R-0DE4B2" -> "0de4b2", the first six of the ref key. */
    static String refKeyPrefix(String ref) {
        String r = ref.trim().toLowerCase(Locale.ROOT);
        if (r.length() > 2 && (r.startsWith("r-") || r.startsWith("d-"))) {
            r = r.substring(2);
        }
        if (!r.matches("[0-9a-f]{6}")) {
            throw new BadRequestException("'" + ref + "' isn't a CoRyd reference like R-0DE4B2 or D-7C34F1");
        }
        return r;
    }

    static String ref(String role, String refKey) {
        return refKey == null ? null : ("DRIVER".equals(role) ? "D-" : "R-") + refKey.substring(0, Math.min(6, refKey.length())).toUpperCase(Locale.ROOT);
    }

    private static String name(String first, String last) {
        String n = ((first == null ? "" : first) + " " + (last == null ? "" : last)).trim();
        return n.isEmpty() ? null : n;
    }

    private static List<String> clean(List<String> values) {
        if (values == null) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        values.stream().filter(v -> v != null && !v.isBlank()).map(String::trim).forEach(out::add);
        return new ArrayList<>(out);
    }
}
