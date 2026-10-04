package org.coryd.payment.dto;

public record SavedCardResponse(
        String id,
        String cardType,
        String last4,
        String expMonth,
        String expYear,
        String bank,
        boolean expired
) {
}
