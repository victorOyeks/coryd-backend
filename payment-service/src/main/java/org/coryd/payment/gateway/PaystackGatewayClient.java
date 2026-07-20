package org.coryd.payment.gateway;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaystackGatewayClient implements PaymentGatewayClient {

    private static final String HMAC_ALGORITHM = "HmacSHA512";

    private final PaystackProperties paystackProperties;

    private RestClient restClient() {
        return RestClient.builder()
                .baseUrl(paystackProperties.getBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + paystackProperties.getSecretKey())
                .build();
    }

    @Override
    public GatewayInitResult initiate(String reference, BigDecimal amount, String riderEmail) {
        // Paystack expects amount in kobo (subunit).
        long amountInKobo = amount.movePointRight(2).longValueExact();

        InitResponse response = restClient().post()
                .uri("/transaction/initialize")
                .body(Map.of(
                        "reference", reference,
                        "amount", amountInKobo,
                        "email", riderEmail == null ? "rider@coryd.ng" : riderEmail
                ))
                .retrieve()
                .body(InitResponse.class);

        return new GatewayInitResult(response.data().authorizationUrl(), reference);
    }

    @Override
    public boolean verifyWebhookSignature(String payload, String signatureHeader) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(paystackProperties.getSecretKey().getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            String computed = HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
            return computed.equalsIgnoreCase(signatureHeader);
        } catch (Exception e) {
            log.error("Failed to verify Paystack webhook signature: {}", e.getMessage());
            return false;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record InitResponse(Data data) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        private record Data(@JsonProperty("authorization_url") String authorizationUrl) {
        }
    }
}
