package org.coryd.payment.dto;

import org.coryd.common.enums.PayoutStatus;

/**
 * {@code status=PAID} with a null {@code authorizationUrl} means a saved card was charged on the spot -
 * show success straight away. {@code status=PENDING} means open {@code authorizationUrl} to finish paying.
 */
public record InitiatePaymentResponse(String authorizationUrl, String gatewayReference, PayoutStatus status) {
}
