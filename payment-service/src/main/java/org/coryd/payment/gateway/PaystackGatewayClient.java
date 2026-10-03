package org.coryd.payment.gateway;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.exception.BadRequestException;
import org.coryd.common.http.HttpTimeouts;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaystackGatewayClient implements PaymentGatewayClient {

    private static final String HMAC_ALGORITHM = "HmacSHA512";

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

        Map<String, Object> body = new HashMap<>(Map.of(
                "reference", reference,
                "amount", amountInKobo,
                "email", riderEmail
        ));
        if (callbackUrl != null && !callbackUrl.isBlank()) {
            body.put("callback_url", callbackUrl);
        }
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

        InitResponse response = restClient().post()
                .uri("/transaction/initialize")
                .body(body)
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

    /** Bank picker for the "Add bank account" screen. */
    public List<BankOption> listBanks() {
        BankListResponse response = restClient().get()
                .uri("/bank?currency=NGN")
                .retrieve()
                .body(BankListResponse.class);
        if (response == null || !response.status()) {
            throw new BadRequestException("Unable to load bank list. Please try again.");
        }
        return response.data();
    }

    /** Resolves the real account holder name from the bank - never trust a client-supplied name for a payout recipient. */
    public ResolvedAccount resolveAccountNumber(String accountNumber, String bankCode) {
        ResolveResponse response = restClient().get()
                .uri("/bank/resolve?account_number={accountNumber}&bank_code={bankCode}", accountNumber, bankCode)
                .retrieve()
                .body(ResolveResponse.class);
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
    public String createSubaccount(String businessName, String accountNumber, String bankCode, BigDecimal percentageCharge) {
        Map<String, Object> body = Map.of(
                "business_name", businessName,
                "settlement_bank", bankCode,
                "account_number", accountNumber,
                "percentage_charge", percentageCharge,
                "settlement_schedule", paystackProperties.getSettlementSchedule()
        );
        SubaccountResponse response = restClient().post()
                .uri("/subaccount")
                .body(body)
                .retrieve()
                .body(SubaccountResponse.class);
        if (response == null || !response.status() || response.data() == null) {
            throw new BadRequestException("Could not register that bank account for earnings. Please try again.");
        }
        return response.data().subaccountCode();
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

    /** Creates the Paystack-side transfer recipient once an account has been resolved - the returned recipient_code is what a later /transfer call targets. */
    public String createTransferRecipient(String accountName, String accountNumber, String bankCode) {
        Map<String, Object> body = Map.of(
                "type", "nuban",
                "name", accountName,
                "account_number", accountNumber,
                "bank_code", bankCode,
                "currency", "NGN"
        );
        RecipientResponse response = restClient().post()
                .uri("/transferrecipient")
                .body(body)
                .retrieve()
                .body(RecipientResponse.class);
        if (response == null || !response.status()) {
            throw new BadRequestException("Could not register that bank account for payouts. Please try again.");
        }
        return response.data().recipientCode();
    }

    /**
     * Fires the actual withdrawal. Treated as provisional - the synchronous status here ("pending"
     * or "success" depending on the bank/route) is not the source of truth; the transfer.success/
     * transfer.failed webhook is, same as how charge collection already treats its webhook as
     * authoritative over the synchronous initiate response.
     */
    public TransferResult initiateTransfer(String reference, BigDecimal amount, String recipientCode, String reason) {
        long amountInKobo = amount.movePointRight(2).longValueExact();
        Map<String, Object> body = Map.of(
                "source", "balance",
                "amount", amountInKobo,
                "recipient", recipientCode,
                "reason", reason,
                "reference", reference
        );
        TransferResponse response = restClient().post()
                .uri("/transfer")
                .body(body)
                .retrieve()
                .body(TransferResponse.class);
        if (response == null || !response.status()) {
            throw new BadRequestException("Withdrawal could not be initiated. Please try again.");
        }
        return new TransferResult(response.data().transferCode(), response.data().status());
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SubaccountResponse(boolean status, Data data) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        private record Data(@JsonProperty("subaccount_code") String subaccountCode) {
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

    public record TransferResult(String transferCode, String status) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BankListResponse(boolean status, List<BankOption> data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ResolveResponse(boolean status, ResolvedAccount data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RecipientResponse(boolean status, Data data) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        private record Data(@JsonProperty("recipient_code") String recipientCode) {
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TransferResponse(boolean status, Data data) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        private record Data(@JsonProperty("transfer_code") String transferCode, String status) {
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record InitResponse(Data data) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        private record Data(@JsonProperty("authorization_url") String authorizationUrl) {
        }
    }
}
