package org.coryd.payment.ops.dto;

import java.math.BigDecimal;
import java.time.Instant;

/** One moment in a transaction's life. actor is set for ops actions. */
public record TimelineEntry(Instant at, String type, BigDecimal amount, String detail, Person actor) {
}
