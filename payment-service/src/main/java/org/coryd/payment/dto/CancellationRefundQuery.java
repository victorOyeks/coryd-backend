package org.coryd.payment.dto;

import java.math.BigDecimal;

/** A cancellation about to be sent: the late fee trip-service will keep (null or 0 for none) and whether the card fee goes back. */
public record CancellationRefundQuery(String bookingId, BigDecimal fee, boolean refundCardFee) {
}
