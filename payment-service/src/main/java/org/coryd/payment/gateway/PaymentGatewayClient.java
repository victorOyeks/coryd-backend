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

    /**
     * Split variant. subaccountCode routes the driver's share straight to their own account so it
     * never lands in ours; flatChargeKobo overrides the subaccount's percentage when we need to
     * take more than our commission, which is how a settlement debt is recovered.
     */
    GatewayInitResult initiateSplit(String reference, BigDecimal amount, String riderEmail, String callbackUrl,
                                    Map<String, String> metadata, String subaccountCode, Long flatChargeKobo);

    /**
     * Charges a card saved from an earlier checkout, with the same split as initiateSplit. A card
     * whose bank wants the rider back (OTP, 3-D Secure) comes back without success and, when the
     * gateway offers one, an authorizationUrl to finish it on.
     */
    ChargeResult chargeAuthorization(String reference, BigDecimal amount, String email, String authorizationCode,
                                     Map<String, String> metadata, String subaccountCode, Long flatChargeKobo);

    /** Best effort - the card is forgotten on our side either way. */
    void deactivateAuthorization(String authorizationCode);

    /** Returns false when the gateway declined the refund outright; a null amount refunds in full. */
    boolean refund(String gatewayReference, BigDecimal amount, String reason);

    boolean verifyWebhookSignature(String payload, String signatureHeader);

    record GatewayInitResult(String authorizationUrl, String gatewayReference) {
    }

    record ChargeResult(boolean success, String authorizationUrl, String gatewayResponse) {
    }
}
