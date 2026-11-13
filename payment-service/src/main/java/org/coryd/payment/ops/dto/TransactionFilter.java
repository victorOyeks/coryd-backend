package org.coryd.payment.ops.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * The Transactions list's filters, every one optional. riderId / driverId / bookingId take a full id or a short reference
 * ("R-A11CE0"); bookingIds (resolved by ops-service from a trip) narrows to those bookings. [from, to) are instants - the
 * caller turns Lagos days into them.
 */
public record TransactionFilter(TransactionType type, String status, Instant from, Instant to, String riderId, String driverId,
                                String bookingId, List<String> bookingIds, BigDecimal amountMin, BigDecimal amountMax,
                                String reference, Sort sort) {

    public enum Sort { NEWEST, OLDEST, AMOUNT_DESC, AMOUNT_ASC }

    public TransactionFilter withType(TransactionType value) {
        return new TransactionFilter(value, status, from, to, riderId, driverId, bookingId, bookingIds, amountMin, amountMax, reference, sort);
    }

    public TransactionFilter withStatus(String value) {
        return new TransactionFilter(type, value, from, to, riderId, driverId, bookingId, bookingIds, amountMin, amountMax, reference, sort);
    }
}
