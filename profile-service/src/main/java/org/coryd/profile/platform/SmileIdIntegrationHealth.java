package org.coryd.profile.platform;

import lombok.RequiredArgsConstructor;
import org.coryd.common.jobs.IntegrationHealthSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/** Smile ID, from the checks profile-service records: PROVIDER_ERROR is a failure on Smile ID's side (or ours), not the driver's. */
@Component
@RequiredArgsConstructor
public class SmileIdIntegrationHealth implements IntegrationHealthSource {

    private final JdbcTemplate jdbc;

    @Override
    public List<IntegrationHealth> integrations(Instant since) {
        Timestamp from = Timestamp.from(since);
        IntegrationHealth smileId = jdbc.queryForObject("""
                select count(*), count(*) filter (where outcome = 'PROVIDER_ERROR'), max(updated_at) filter (where outcome = 'PROVIDER_ERROR'),
                       (select c.provider_summary from profile.verification_checks c where c.outcome = 'PROVIDER_ERROR' and c.updated_at >= ?
                        order by c.updated_at desc limit 1)
                from profile.verification_checks where updated_at >= ?
                """, (rs, i) -> new IntegrationHealth("SMILE_ID", "verification checks", rs.getLong(1), rs.getLong(2),
                rs.getTimestamp(3) == null ? null : rs.getTimestamp(3).toInstant(), rs.getString(4)), from, from);
        return List.of(smileId);
    }
}
