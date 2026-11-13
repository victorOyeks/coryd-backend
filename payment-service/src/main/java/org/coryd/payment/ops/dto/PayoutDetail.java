package org.coryd.payment.ops.dto;

import java.util.List;

public record PayoutDetail(PayoutItem payout, PayoutAccountView account, List<OpsMoneyRequestView> retries,
                           List<TimelineEntry> timeline) {
}
