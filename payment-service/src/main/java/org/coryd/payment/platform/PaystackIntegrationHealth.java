package org.coryd.payment.platform;

import lombok.RequiredArgsConstructor;
import org.coryd.common.jobs.IntegrationHealthSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/** Paystack, counted from payment-service's own records: charges, refunds, settlements and ops money actions that failed. */
@Component
@RequiredArgsConstructor
public class PaystackIntegrationHealth implements IntegrationHealthSource {

    private final JdbcTemplate jdbc;

    @Override
    public List<IntegrationHealth> integrations(Instant since) {
        Timestamp from = Timestamp.from(since);
        return List.of(
                count("charges", "select count(*), count(*) filter (where status = 'FAILED'), max(updated_at) filter (where status = 'FAILED'),"
                        + " null from payment.payment_transactions where updated_at >= ?", from),
                count("refunds", "select count(*), count(*) filter (where status = 'FAILED'), max(updated_at) filter (where status = 'FAILED'),"
                        + " null from payment.refunds where updated_at >= ?", from),
                count("settlements", "select count(*), count(*) filter (where lower(status) = 'failed'),"
                        + " max(updated_at) filter (where lower(status) = 'failed'), null from payment.driver_settlements where updated_at >= ?", from),
                count("ops refunds and retries", "select count(*), count(*) filter (where failure_reason is not null),"
                        + " max(updated_at) filter (where failure_reason is not null),"
                        + " (select r.failure_reason from payment.ops_money_requests r where r.failure_reason is not null and r.updated_at >= ?"
                        + "  order by r.updated_at desc limit 1)"
                        + " from payment.ops_money_requests where updated_at >= ?", from, from));
    }

    private IntegrationHealth count(String operation, String sql, Object... args) {
        return jdbc.queryForObject(sql, (rs, i) -> new IntegrationHealth("PAYSTACK", operation, rs.getLong(1), rs.getLong(2),
                rs.getTimestamp(3) == null ? null : rs.getTimestamp(3).toInstant(), rs.getString(4)), args);
    }
}
