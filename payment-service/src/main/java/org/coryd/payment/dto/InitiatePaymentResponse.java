package org.coryd.payment.dto;

import org.coryd.common.enums.PayoutStatus;

/**
 * {@code status=PAID} with a null {@code authorizationUrl} means the wallet path paid
 * synchronously - the client shows success immediately instead of opening a browser.
 * {@code status=PENDING} means the Paystack redirect flow needs {@code authorizationUrl}.
 */
public record InitiatePaymentResponse(String authorizationUrl, String gatewayReference, PayoutStatus status) {
}
