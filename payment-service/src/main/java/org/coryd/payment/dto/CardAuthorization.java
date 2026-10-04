package org.coryd.payment.dto;

/** What Paystack reports about the card behind a successful charge. */
public record CardAuthorization(
        String authorizationCode,
        String signature,
        String channel,
        boolean reusable,
        String cardType,
        String last4,
        String expMonth,
        String expYear,
        String bank,
        String email
) {
}
