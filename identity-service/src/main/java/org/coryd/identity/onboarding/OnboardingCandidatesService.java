package org.coryd.identity.onboarding;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Who notification-service's daily onboarding reminders may consider (docs/ops-console/onboarding-reminders.md): rider
 * and driver accounts that can sign in - phone verified, password set, active, not suspended, no account deletion
 * waiting. Whether they finished onboarding is profile-service's to say; this only lists the accounts, a page at a time
 * by user id (keyset), with what only identity knows: when they signed up and when they accepted the terms.
 */
@Service
@RequiredArgsConstructor
public class OnboardingCandidatesService {

    public static final int MAX_PAGE = 1000;

    public record Candidate(String userId, String firstName, List<String> roles, Instant signedUpAt, Instant termsAcceptedAt) {
    }

    public record Page(List<Candidate> people, String nextAfter) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public Page page(String after, int size) {
        int limit = Math.max(1, Math.min(size, MAX_PAGE));
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("after", after == null ? "" : after).addValue("limit", limit + 1);
        List<Candidate> rows = new ArrayList<>(jdbc.query("""
                SELECT u.id, u.first_name, u.created_at, u.terms_accepted_at,
                       (SELECT string_agg(r.role, ',' ORDER BY r.role) FROM identity.user_roles r
                        WHERE r.user_id = u.id AND r.role IN ('RIDER', 'DRIVER')) AS roles
                FROM identity.users u
                WHERE u.id > :after
                  AND u.active
                  AND u.suspended_at IS NULL
                  AND u.phone_verified_at IS NOT NULL
                  AND u.password_hash IS NOT NULL
                  AND EXISTS (SELECT 1 FROM identity.user_roles r WHERE r.user_id = u.id AND r.role IN ('RIDER', 'DRIVER'))
                  AND NOT EXISTS (SELECT 1 FROM identity.account_deletion_requests d WHERE d.user_id = u.id AND d.status = 'PENDING')
                ORDER BY u.id
                LIMIT :limit
                """, p, (rs, i) -> new Candidate(rs.getString("id"), rs.getString("first_name"),
                Arrays.asList(rs.getString("roles").split(",")), instant(rs.getObject("created_at", java.time.LocalDateTime.class)),
                instant(rs.getObject("terms_accepted_at", java.time.LocalDateTime.class)))));
        String next = null;
        if (rows.size() > limit) {
            rows = new ArrayList<>(rows.subList(0, limit));
            next = rows.get(rows.size() - 1).userId();
        }
        return new Page(rows, next);
    }

    /** Hibernate stores Instants as UTC wall-clock time in these TIMESTAMP columns. */
    private static Instant instant(java.time.LocalDateTime utc) {
        return utc == null ? null : utc.toInstant(java.time.ZoneOffset.UTC);
    }
}
