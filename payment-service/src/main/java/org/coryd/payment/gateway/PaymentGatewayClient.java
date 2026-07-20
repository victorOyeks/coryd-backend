package org.coryd.payment.gateway;

import java.math.BigDecimal;

/**
 * Vendor-agnostic seam over the third-party NG payment gateway (PRD leaves the
 * exact vendor open - default implementation targets Paystack). Swapping
 * providers means adding a new implementation, not touching PaymentService.
 */
public interface PaymentGatewayClient {

    GatewayInitResult initiate(String reference, BigDecimal amount, String riderEmail);

    boolean verifyWebhookSignature(String payload, String signatureHeader);

    record GatewayInitResult(String authorizationUrl, String gatewayReference) {
    }
}
