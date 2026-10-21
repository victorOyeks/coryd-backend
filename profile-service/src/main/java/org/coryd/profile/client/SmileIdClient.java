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
import java.util.Base64;
import java.util.UUID;

/**
 * The two things the backend does with Smile ID: mint the short-lived token the app's SDK submits
 * with, and check that a webhook really came from Smile ID. Everything else goes app to Smile ID.
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
