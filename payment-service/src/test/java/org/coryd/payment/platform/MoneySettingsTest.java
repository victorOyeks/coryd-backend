package org.coryd.payment.platform;

import org.coryd.common.kafka.events.PlatformSettingChangedEvent;
import org.coryd.common.settings.PlatformSettings;
import org.coryd.payment.config.CommissionProperties;
import org.coryd.payment.ops.OpsMoneyProperties;
import org.coryd.payment.service.SubaccountBackfill;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/** payment-service's money rules read ops' live values, fall back to their env vars, and a commission change re-syncs Paystack. */
class MoneySettingsTest {

    @Test
    void commissionAndTheApprovalThresholdFollowOpsSettings() {
        PlatformSettings settings = new PlatformSettings();
        CommissionProperties commission = new CommissionProperties();
        OpsMoneyProperties money = new OpsMoneyProperties();
        assertThat(commission.getPercentage()).as("no registry: the property").isEqualByComparingTo("0.05");
        ReflectionTestUtils.setField(commission, "platformSettings", settings);
        ReflectionTestUtils.setField(money, "platformSettings", settings);
        assertThat(commission.getPercentage()).isEqualByComparingTo("0.05");
        assertThat(money.needsApproval(new BigDecimal("60000"))).isTrue();

        settings.apply(event("commission.percent", "7.5", 1));
        settings.apply(event("money.approval-threshold", "100000", 1));
        assertThat(commission.getPercentage()).isEqualByComparingTo("0.075");
        assertThat(commission.paystackPercentageCharge()).isEqualByComparingTo("7.50");
        assertThat(money.getApprovalThreshold()).isEqualByComparingTo("100000");
        assertThat(money.needsApproval(new BigDecimal("60000"))).isFalse();

        settings.apply(event("commission.percent", null, 2));
        assertThat(commission.getPercentage()).as("reset").isEqualByComparingTo("0.05");
    }

    @Test
    void aCommissionChangeResyncsTheSubaccountsAndNothingElseDoes() {
        PlatformSettings settings = new PlatformSettings();
        SubaccountBackfill backfill = mock(SubaccountBackfill.class);
        new CommissionChangeListener(settings, backfill);
        settings.apply(event("money.approval-threshold", "100000", 1));
        verify(backfill, after(300).never()).syncSubaccounts();
        settings.apply(event("commission.percent", "6", 1));
        verify(backfill, timeout(2000)).syncSubaccounts();
    }

    private static PlatformSettingChangedEvent event(String key, String value, long version) {
        return PlatformSettingChangedEvent.builder().key(key).value(value).version(version).changedAt(Instant.now()).build();
    }
}
