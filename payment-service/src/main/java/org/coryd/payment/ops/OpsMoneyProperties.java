package org.coryd.payment.ops;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;

/** Ops Console money rules. */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "ops.money")
public class OpsMoneyProperties {

    /** A manual refund, refund retry, payout retry or debt adjustment for more than this needs a second admin (decision 4). */
    private BigDecimal approvalThreshold = new BigDecimal("50000");

    /** A refund still PENDING after this long (and not on the automatic retry) is listed as stuck. */
    private long stuckRefundMinutes = 60;

    /** Longest finance dashboard window, in days. */
    private int maxDashboardDays = 366;

    public boolean needsApproval(BigDecimal amount) {
        return amount != null && amount.compareTo(approvalThreshold) > 0;
    }
}
