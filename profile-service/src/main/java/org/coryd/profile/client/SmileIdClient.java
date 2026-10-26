package org.coryd.profile.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.exception.ExternalServiceException;
import org.coryd.common.http.HttpTimeouts;
import org.coryd.profile.config.SmileIdProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * What the backend does with Smile ID: mint the short-lived token the app's SDK submits with, submit a
 * driver's NIN lookup (the one check sent from here, not the app), and check that a webhook really came
 * from Smile ID.
 */
@Slf4j
@Component
public class SmileIdClient {

    private final SmileIdProperties properties;
    private final RestClient restClient;

    @Autowired
    public SmileIdClient(SmileIdProperties properties) {
        this(properties, properties.baseUrl());
    }

    /** baseUrl is only ever overridden by tests, to capture what we send. */
    SmileIdClient(SmileIdProperties properties, String baseUrl) {
        this.properties = properties;
        this.restClient = HttpTimeouts.builder(HttpTimeouts.DEFAULT_CONNECT_TIMEOUT, HttpTimeouts.DEFAULT_READ_TIMEOUT)
                .baseUrl(baseUrl)
                .build();
    }

    /**
     * POST /v3/token - multipart only; a JSON body gets a 415. A 401 usually means a sandbox key against live
     * or the reverse. The multipart body is written by hand: Smile ID only accepts the content type as
     * "multipart/form-data; boundary=..." (with the space), and Spring's own writer leaves the space out.
     */
    public String mintToken() {
        String boundary = "coryd-" + UUID.randomUUID();
        byte[] body = ("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"partner_id\"\r\n\r\n"
                + properties.getPartnerId() + "\r\n"
                + "--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        try {
            TokenResponse response = restClient.post()
                    .uri("/v3/token")
                    .header("smileid-partner-id", properties.getPartnerId())
                    .header("smileid-api-key", properties.getApiKey())
                    .header(HttpHeaders.CONTENT_TYPE, "multipart/form-data; boundary=" + boundary)
                    .body(body)
                    .retrieve()
                    .body(TokenResponse.class);
            if (response == null || response.token() == null || response.token().isBlank()) {
                throw new IllegalStateException("no token in the response");
            }
            return response.token();
        } catch (Exception e) {
            log.error("Smile ID token request failed (sandbox={}): {}", properties.isSandbox(), e.getMessage());
            throw new ExternalServiceException("Identity checks are unavailable right now - try again in a few minutes", e);
        }
    }

    /**
     * POST /v3/enhanced_kyc - a NIN looked up with NIMC by number, no selfie. Accepted straight away (202);
     * the result comes to the same webhook as every other check, carrying checkId in partner_params.
     * Multipart only, nested fields written as user_details[given_names] and so on.
     */
    public void submitNinLookup(NinLookup lookup) {
        String token = mintToken();
        List<String[]> fields = new ArrayList<>();
        fields.add(new String[]{"country", "NG"});
        fields.add(new String[]{"id_type", lookup.idType()});
        fields.add(new String[]{"id_number", lookup.nin()});
        fields.add(new String[]{"user_details[given_names]", lookup.givenNames()});
        fields.add(new String[]{"user_details[last_name]", lookup.lastName()});
        if (lookup.email() != null) {
            fields.add(new String[]{"user_details[email]", lookup.email()});
        }
        if (lookup.phone() != null) {
            fields.add(new String[]{"user_details[phone_number]", lookup.phone()});
        }
        fields.add(new String[]{"consent[granted]", "true"});
        fields.add(new String[]{"consent[granted_at]", lookup.consentAt().toString()});
        fields.add(new String[]{"consent[notice_language]", "EN"});
        fields.add(new String[]{"consent[notice_privacy_policy_url]", properties.getPrivacyNoticeUrl()});
        fields.add(new String[]{"partner_params[coryd_check_id]", lookup.checkId()});
        if (properties.getCallbackUrl() != null && !properties.getCallbackUrl().isBlank()) {
            fields.add(new String[]{"callback_url", properties.getCallbackUrl()});
        }
        String boundary = "coryd-" + UUID.randomUUID();
        StringBuilder body = new StringBuilder();
        for (String[] field : fields) {
            body.append("--").append(boundary).append("\r\n")
                    .append("Content-Disposition: form-data; name=\"").append(field[0]).append("\"\r\n\r\n")
                    .append(field[1]).append("\r\n");
        }
        body.append("--").append(boundary).append("--\r\n");
        try {
            restClient.post()
                    .uri("/v3/enhanced_kyc")
                    .header("SmileID-Token", token)
                    .header(HttpHeaders.CONTENT_TYPE, "multipart/form-data; boundary=" + boundary)
                    .body(body.toString().getBytes(StandardCharsets.UTF_8))
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.error("Smile ID NIN lookup {} not accepted (sandbox={}): {}", lookup.checkId(), properties.isSandbox(), e.getMessage());
            throw new ExternalServiceException("Identity checks are unavailable right now - try again in a few minutes", e);
        }
    }

    /** phone in E.164 (+234...); Smile ID needs an email or a phone. */
    public record NinLookup(String checkId, String idType, String nin, String givenNames, String lastName,
                            String email, String phone, Instant consentAt) {
    }

    /**
     * Base64 HMAC-SHA256, keyed with our API key, of Response-Timestamp + partner ID + "sid_request",
     * compared to the Response-Signature header in constant time.
     */
    public boolean signatureValid(String timestamp, String signature) {
        if (timestamp == null || signature == null || !properties.isConfigured()) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.getApiKey().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = mac.doFinal((timestamp + properties.getPartnerId() + "sid_request").getBytes(StandardCharsets.UTF_8));
            return MessageDigest.isEqual(Base64.getEncoder().encode(expected), signature.trim().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("Could not check a Smile ID signature: {}", e.getMessage());
            return false;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TokenResponse(String token) {
    }
}
