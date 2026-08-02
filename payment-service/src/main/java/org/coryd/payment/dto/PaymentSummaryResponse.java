package org.coryd.payment.dto;

import org.coryd.common.enums.PaymentMethod;
import org.coryd.common.enums.PayoutStatus;

public record PaymentSummaryResponse(PaymentMethod paymentMethod, PayoutStatus status) {
}
