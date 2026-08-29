package org.coryd.payment.dto;

import java.math.BigDecimal;

/** percentage is a plain percentage number (e.g. 15 means 15%), not the 0.15 fraction commission.percentage is stored/used as internally. */
public record CommissionRate(BigDecimal percentage) {
}
