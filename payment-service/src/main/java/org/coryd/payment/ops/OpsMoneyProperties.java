package org.coryd.payment.ops;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.coryd.common.settings.PlatformSetting;
import org.coryd.common.settings.PlatformSettings;
import org.springframework.beans.factory.annotation.Autowired;
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

    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    @Autowired(required = false)
    private PlatformSettings platformSettings;

    /** Ops' runtime value (money.approval-threshold), else OPS_MONEY_APPROVAL_THRESHOLD. */
    public BigDecimal getApprovalThreshold() {
        return PlatformSettings.decimal(platformSettings, PlatformSetting.MONEY_APPROVAL_THRESHOLD, approvalThreshold);
    }

    public boolean needsApproval(BigDecimal amount) {
        return amount != null && amount.compareTo(getApprovalThreshold()) > 0;
    }
}
