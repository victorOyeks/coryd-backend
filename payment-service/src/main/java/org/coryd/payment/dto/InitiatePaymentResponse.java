package org.coryd.payment.dto;

public record InitiatePaymentResponse(String authorizationUrl, String gatewayReference) {
}
