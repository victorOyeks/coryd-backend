package org.coryd.payment.gateway;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Vendor-agnostic seam over the third-party NG payment gateway (PRD leaves the
 * exact vendor open - default implementation targets Paystack). Swapping
 * providers means adding a new implementation, not touching PaymentService.
 */
public interface PaymentGatewayClient {

    /** metadata comes back on the webhook - what ties a charge to its booking if the reference alone no longer does. */
    GatewayInitResult initiate(String reference, BigDecimal amount, String riderEmail, String callbackUrl, Map<String, String> metadata);

    boolean verifyWebhookSignature(String payload, String signatureHeader);

    record GatewayInitResult(String authorizationUrl, String gatewayReference) {
    }
}
