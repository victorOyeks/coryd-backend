package org.coryd.profile.client;

import org.coryd.profile.config.SmileIdProperties;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/** Only a webhook signed with our API key, over Response-Timestamp + partner ID + "sid_request", is read. */
class SmileIdClientTest {

    private static final String TIMESTAMP = "2026-10-17T10:00:00.000Z";

    @Test
    void acceptsSmileIdsSignatureAndNothingElse() throws Exception {
        SmileIdProperties properties = new SmileIdProperties();
        properties.setPartnerId("1234");
        properties.setApiKey("secret-key");
        SmileIdClient client = new SmileIdClient(properties);
        String signature = sign("secret-key", TIMESTAMP + "1234" + "sid_request");

        assertThat(client.signatureValid(TIMESTAMP, signature)).isTrue();
        assertThat(client.signatureValid("2026-10-17T10:00:01.000Z", signature)).as("different timestamp").isFalse();
        assertThat(client.signatureValid(TIMESTAMP, sign("another-key", TIMESTAMP + "1234sid_request"))).as("wrong key").isFalse();
        assertThat(client.signatureValid(TIMESTAMP, null)).isFalse();
        assertThat(client.signatureValid(null, signature)).isFalse();
    }

    @Test
    void rejectsEverythingWhenNotConfigured() throws Exception {
        SmileIdClient client = new SmileIdClient(new SmileIdProperties());

        assertThat(client.signatureValid(TIMESTAMP, sign("", TIMESTAMP + "sid_request"))).isFalse();
    }

    private static String sign(String key, String message) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.isEmpty() ? new byte[1] : key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
    }
}
