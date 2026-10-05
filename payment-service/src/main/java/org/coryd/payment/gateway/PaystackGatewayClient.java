package org.coryd.payment.gateway;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.exception.BadRequestException;
import org.coryd.common.exception.ExternalServiceException;
import org.coryd.common.http.HttpTimeouts;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaystackGatewayClient implements PaymentGatewayClient {

    private static final String HMAC_ALGORITHM = "HmacSHA512";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final PaystackProperties paystackProperties;

    private RestClient restClient() {

        return HttpTimeouts.builder(Duration.ofSeconds(5), Duration.ofSeconds(20))
                .baseUrl(paystackProperties.getBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + paystackProperties.getSecretKey())
                .build();
    }

    @Override
    public GatewayInitResult initiate(String reference, BigDecimal amount, String riderEmail, String callbackUrl, Map<String, String> metadata) {
        return initiateSplit(reference, amount, riderEmail, callbackUrl, metadata, null, null);
    }

    @Override
    public GatewayInitResult initiateSplit(String reference, BigDecimal amount, String riderEmail, String callbackUrl,
                                           Map<String, String> metadata, String subaccountCode, Long flatChargeKobo) {
        if (riderEmail == null || riderEmail.isBlank()) {
            // Map.of would NPE here; Paystack rejects the call anyway, so fail with something readable.
            throw new BadRequestException("A billing email is required to start a payment");
        }
        long amountInKobo = amount.movePointRight(2).longValueExact();

        Map<String, Object> body = chargeBody(reference, amountInKobo, riderEmail, metadata, subaccountCode, flatChargeKobo);
        if (callbackUrl != null && !callbackUrl.isBlank()) {
            body.put("callback_url", callbackUrl);
        }

        InitResponse response = restClient().post()
                .uri("/transaction/initialize")
                .body(body)
                .retrieve()
                .body(InitResponse.class);

        return new GatewayInitResult(response.data().authorizationUrl(), reference);
    }

    @Override
    public ChargeResult chargeAuthorization(String reference, BigDecimal amount, String email, String authorizationCode,
                                            Map<String, String> metadata, String subaccountCode, Long flatChargeKobo) {
        Map<String, Object> body = chargeBody(reference, amount.movePointRight(2).longValueExact(), email,
                metadata, subaccountCode, flatChargeKobo);
        body.put("authorization_code", authorizationCode);
        ChargeResponse response = restClient().post()
                .uri("/transaction/charge_authorization")
                .body(body)
                .retrieve()
                .body(ChargeResponse.class);
        if (response == null || response.data() == null) {
            return new ChargeResult(false, null, response == null ? null : response.message());
        }
        return new ChargeResult("success".equalsIgnoreCase(response.data().status()),
                response.data().authorizationUrl(), response.data().gatewayResponse());
    }

    @Override
    public void deactivateAuthorization(String authorizationCode) {
        try {
            restClient().post()
                    .uri("/customer/deactivate_authorization")
                    .body(Map.of("authorization_code", authorizationCode))
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.warn("Paystack did not deactivate a removed card: {}", e.getMessage());
        }
    }

    /** The split is set per charge, so a saved-card charge carries it exactly like a checkout does. */
    private Map<String, Object> chargeBody(String reference, long amountInKobo, String email, Map<String, String> metadata,
                                           String subaccountCode, Long flatChargeKobo) {
        Map<String, Object> body = new HashMap<>(Map.of(
                "reference", reference,
                "amount", amountInKobo,
                "email", email
        ));
        if (metadata != null && !metadata.isEmpty()) {
            body.put("metadata", metadata);
        }
        if (subaccountCode != null && !subaccountCode.isBlank()) {
            body.put("subaccount", subaccountCode);
            String bearer = paystackProperties.getSplitFeeBearer();
            if (bearer != null && !bearer.isBlank()) {
                body.put("bearer", bearer);
            }
            // Overrides the subaccount's own percentage for this one charge.
            if (flatChargeKobo != null && flatChargeKobo > 0) {
                body.put("transaction_charge", flatChargeKobo);
            }
        }
        return body;
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

    @PostConstruct
    void warnAboutTestSettings() {
        String testSubaccount = paystackProperties.getTestSubaccountCode();
        if (testSubaccount != null && !testSubaccount.isBlank()) {
            if (paystackProperties.isTestKey()) {
                log.warn("PAYSTACK_TEST_SUBACCOUNT_CODE={} - every driver's fares are split to this one test subaccount", testSubaccount);
            } else {
                log.error("PAYSTACK_TEST_SUBACCOUNT_CODE is set with a live key - ignoring it, each driver gets their own subaccount");
            }
        }
        String testBank = paystackProperties.getTestBankCode();
        if (testBank != null && !testBank.isBlank()) {
            if (paystackProperties.isTestKey()) {
                log.warn("PAYSTACK_TEST_BANK_CODE={} - bank accounts are sent to Paystack as its test bank, not the driver's real one", testBank);
            } else {
                log.error("PAYSTACK_TEST_BANK_CODE is set with a live key - ignoring it, real bank details are used");
            }
        }
    }

    /**
     * Paystack's refusal comes back as a readable 400 with its own message (a bad account number, a test
     * mode limit), and an outage as a 502 - never a 500 for something the driver can act on.
     */
    private <T> T call(String operation, String failure, Supplier<T> request) {
        try {
            return request.get();
        } catch (RestClientResponseException e) {
            String message = paystackMessage(e);
            log.warn("Paystack refused {}: {} {}", operation, e.getStatusCode(), message);
            if (e.getStatusCode().is4xxClientError()) {
                throw new BadRequestException(message != null ? message : failure);
            }
            throw new ExternalServiceException(failure, e);
        } catch (ResourceAccessException e) {
            log.warn("Paystack unreachable for {}: {}", operation, e.getMessage());
            throw new ExternalServiceException(failure, e);
        }
    }

    private static String paystackMessage(RestClientResponseException e) {
        try {
            JsonNode body = JSON.readTree(e.getResponseBodyAsString());
            return body.hasNonNull("message") ? body.get("message").asText() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Bank picker for the "Add bank account" screen. */
    public List<BankOption> listBanks() {
        BankListResponse response = call("bank list", "Unable to load bank list. Please try again.", () -> restClient().get()
                .uri("/bank?currency=NGN")
                .retrieve()
                .body(BankListResponse.class));
        if (response == null || !response.status()) {
            throw new BadRequestException("Unable to load bank list. Please try again.");
        }
        return response.data();
    }

    /** Resolves the real account holder name from the bank - never trust a client-supplied name for a payout recipient. */
    public ResolvedAccount resolveAccountNumber(String accountNumber, String bankCode) {
        String gatewayBank = paystackProperties.gatewayBankCode(bankCode);
        ResolveResponse response = call("account resolve", "Could not verify that account number - please try again.", () -> restClient().get()
                .uri("/bank/resolve?account_number={accountNumber}&bank_code={bankCode}", accountNumber, gatewayBank)
                .retrieve()
                .body(ResolveResponse.class));
        if (response == null || !response.status()) {
            throw new BadRequestException("Could not verify that account number - please check the details and try again.");
        }
        return response.data();
    }

    /**
     * percentageCharge is the SUBACCOUNT's share, not ours - 95 leaves us the 5% commission. The
     * settlement schedule must stay a fixed calendar; releasing on trip completion is what Paystack
     * refused to approve.
     */
    public SubaccountResult createSubaccount(String businessName, String accountNumber, String bankCode, BigDecimal percentageCharge) {
        String testSubaccount = paystackProperties.testSubaccount();
        if (testSubaccount != null) {
            // Its numeric id is looked up by the settlement sync on first use.
            return new SubaccountResult(testSubaccount, null);
        }
        Map<String, Object> body = Map.of(
                "business_name", businessName,
                "settlement_bank", paystackProperties.gatewayBankCode(bankCode),
                "account_number", accountNumber,
                "percentage_charge", percentageCharge,
                "settlement_schedule", paystackProperties.getSettlementSchedule()
        );
        SubaccountResponse response = call("subaccount", "Could not register that bank account for earnings. Please try again.", () -> restClient().post()
                .uri("/subaccount")
                .body(body)
                .retrieve()
                .body(SubaccountResponse.class));
        if (response == null || !response.status() || response.data() == null) {
            throw new BadRequestException("Could not register that bank account for earnings. Please try again.");
        }
        return new SubaccountResult(response.data().subaccountCode(), response.data().id());
    }

    /** For subaccounts created before we kept the id. */
    public String fetchSubaccountId(String subaccountCode) {
        SubaccountResponse response = restClient().get()
                .uri("/subaccount/{code}", subaccountCode)
                .retrieve()
                .body(SubaccountResponse.class);
        return response == null || response.data() == null ? null : response.data().id();
    }

    /** One page of a subaccount's settlements to its bank, newest first. */
    public List<Settlement> listSettlements(String subaccountId, java.time.Instant from, int page, int perPage) {
        SettlementListResponse response = restClient().get()
                .uri(b -> b.path("/settlement")
                        .queryParam("subaccount", subaccountId)
                        .queryParam("from", from.toString())
                        .queryParam("page", page)
                        .queryParam("perPage", perPage)
                        .build())
                .retrieve()
                .body(SettlementListResponse.class);
        return response == null || response.data() == null ? List.of() : response.data();
    }

    /**
     * Before the driver's share settles, Paystack takes it back out of the split and we are not out
     * of pocket. After settlement the whole refund comes off our balance and the driver's share
     * becomes a debt we recover ourselves.
     */
    @Override
    public boolean refund(String gatewayReference, BigDecimal amount, String reason) {
        Map<String, Object> body = new HashMap<>(Map.of("transaction", gatewayReference));
        if (amount != null) {
            body.put("amount", amount.movePointRight(2).longValueExact());
        }
        if (reason != null && !reason.isBlank()) {
            body.put("merchant_note", reason);
        }
        try {
            RefundResponse response = restClient().post()
                    .uri("/refund")
                    .body(body)
                    .retrieve()
                    .body(RefundResponse.class);
            return response != null && response.status();
        } catch (Exception e) {
            log.error("Paystack refund failed for {}: {}", gatewayReference, e.getMessage());
            return false;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SubaccountResponse(boolean status, Data data) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        private record Data(@JsonProperty("subaccount_code") String subaccountCode, String id) {
        }
    }

    public record SubaccountResult(String code, String id) {
    }

    /** Amounts are in kobo. status is success, processing, pending or failed. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Settlement(String id, String status,
                             @JsonProperty("total_amount") Long totalAmount,
                             @JsonProperty("effective_amount") Long effectiveAmount,
                             @JsonProperty("settlement_date") String settlementDate) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SettlementListResponse(boolean status, List<Settlement> data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ChargeResponse(boolean status, String message, Data data) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        private record Data(String status, @JsonProperty("gateway_response") String gatewayResponse,
                            @JsonProperty("authorization_url") String authorizationUrl) {
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RefundResponse(boolean status) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BankOption(String name, String code) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ResolvedAccount(@JsonProperty("account_number") String accountNumber,
                                   @JsonProperty("account_name") String accountName) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BankListResponse(boolean status, List<BankOption> data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ResolveResponse(boolean status, ResolvedAccount data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record InitResponse(Data data) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        private record Data(@JsonProperty("authorization_url") String authorizationUrl) {
        }
    }
}
