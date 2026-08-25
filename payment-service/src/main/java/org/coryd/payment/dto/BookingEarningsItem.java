package org.coryd.payment.dto;

import java.math.BigDecimal;

public record BookingEarningsItem(String bookingId, BigDecimal grossFare, BigDecimal commissionAmount, BigDecimal netCredit) {
}
