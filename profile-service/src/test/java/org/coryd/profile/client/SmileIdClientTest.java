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
    void jobImagesAreFetchedWithASignedJobStatusRequest() throws Exception {
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        java.util.concurrent.atomic.AtomicReference<String> body = new java.util.concurrent.atomic.AtomicReference<>();
        server.createContext("/v1/job_status", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] reply = ("{\"job_complete\":true,\"ImageLinks\":{\"id_card_image\":\"https://s3/front.jpg\","
                    + "\"id_card_back\":\"https://s3/back.jpg\",\"selfie_image\":\"https://s3/selfie.jpg\",\"error\":\"none\"}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, reply.length);
            exchange.getResponseBody().write(reply);
            exchange.close();
        });
        server.start();
        try {
            SmileIdProperties properties = new SmileIdProperties();
            properties.setPartnerId("8371");
            properties.setApiKey("secret-key");
            var images = new SmileIdClient(properties, "http://127.0.0.1:" + server.getAddress().getPort()).jobImages("smile-user", "smile-job");

            assertThat(images).containsOnlyKeys("id_card_image", "id_card_back", "selfie_image")
                    .containsEntry("id_card_image", "https://s3/front.jpg");
            var sent = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body.get());
            assertThat(sent.get("partner_id").asText()).isEqualTo("8371");
            assertThat(sent.get("user_id").asText()).isEqualTo("smile-user");
            assertThat(sent.get("job_id").asText()).isEqualTo("smile-job");
            assertThat(sent.get("image_links").asBoolean()).isTrue();
            assertThat(sent.get("signature").asText()).as("signed like Smile ID's own webhooks")
                    .isEqualTo(sign("secret-key", sent.get("timestamp").asText() + "8371sid_request"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aJobStatusFailureSaysToUseThePortal() throws Exception {
        SmileIdProperties properties = new SmileIdProperties();
        properties.setPartnerId("8371");
        properties.setApiKey("secret-key");
        SmileIdClient client = new SmileIdClient(properties, "http://127.0.0.1:1");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> client.jobImages("u", "j"))
                .isInstanceOf(org.coryd.common.exception.ExternalServiceException.class)
                .hasMessageContaining("Smile ID portal");
    }

    @Test
    void rejectsEverythingWhenNotConfigured() throws Exception {
        SmileIdClient client = new SmileIdClient(new SmileIdProperties());

        assertThat(client.signatureValid(TIMESTAMP, sign("", TIMESTAMP + "sid_request"))).isFalse();
    }

    @Test
    void theTokenRequestIsMultipartTheWaySmileIdAcceptsIt() throws Exception {
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        java.util.concurrent.atomic.AtomicReference<String> contentType = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> body = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> apiKey = new java.util.concurrent.atomic.AtomicReference<>();
        server.createContext("/v3/token", exchange -> {
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            apiKey.set(exchange.getRequestHeaders().getFirst("smileid-api-key"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] reply = "{\"token\":\"short-lived\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, reply.length);
            exchange.getResponseBody().write(reply);
            exchange.close();
        });
        server.start();
        try {
            SmileIdProperties properties = new SmileIdProperties();
            properties.setPartnerId("8371");
            properties.setApiKey("secret-key");
            String token = new SmileIdClient(properties, "http://127.0.0.1:" + server.getAddress().getPort()).mintToken();

            assertThat(token).isEqualTo("short-lived");
            // Smile ID answers 415 to "multipart/form-data;boundary=..." - the space after the semicolon is required.
            assertThat(contentType.get()).startsWith("multipart/form-data; boundary=");
            String boundary = contentType.get().substring("multipart/form-data; boundary=".length());
            assertThat(body.get()).contains("--" + boundary + "\r\n")
                    .contains("Content-Disposition: form-data; name=\"partner_id\"\r\n\r\n8371\r\n")
                    .endsWith("--" + boundary + "--\r\n");
            assertThat(apiKey.get()).isEqualTo("secret-key");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void theNinLookupSendsEachNestedObjectAsOneJsonField() throws Exception {
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        java.util.concurrent.atomic.AtomicReference<String> body = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> token = new java.util.concurrent.atomic.AtomicReference<>();
        server.createContext("/v3/token", exchange -> {
            byte[] reply = "{\"token\":\"short-lived\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, reply.length);
            exchange.getResponseBody().write(reply);
            exchange.close();
        });
        server.createContext("/v3/enhanced_kyc", exchange -> {
            token.set(exchange.getRequestHeaders().getFirst("SmileID-Token"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
        });
        server.start();
        try {
            SmileIdProperties properties = new SmileIdProperties();
            properties.setPartnerId("8371");
            properties.setApiKey("secret-key");
            new SmileIdClient(properties, "http://127.0.0.1:" + server.getAddress().getPort()).submitNinLookup(new SmileIdClient.NinLookup(
                    "coryd-ninl-1", "NIN_V2", "22233344455", "Mayowa", "Ayeni", null, "+2348012345678",
                    java.time.Instant.parse("2026-10-26T16:28:36Z")));

            assertThat(token.get()).isEqualTo("short-lived");
            // "consent is required" is what Smile ID answers to bracketed names like consent[granted].
            assertThat(body.get()).doesNotContain("consent[")
                    .contains("name=\"consent\"\r\n\r\n{\"granted\":true,\"granted_at\":\"2026-10-26T16:28:36Z\",\"notice_language\":\"EN\","
                            + "\"notice_privacy_policy_url\":\"https://coryd.ng/privacy\"}\r\n")
                    .contains("name=\"user_details\"\r\n\r\n{\"given_names\":\"Mayowa\",\"last_name\":\"Ayeni\",\"phone_number\":\"+2348012345678\"}\r\n")
                    .contains("name=\"partner_params\"\r\n\r\n{\"coryd_check_id\":\"coryd-ninl-1\"}\r\n")
                    .contains("name=\"id_number\"\r\n\r\n22233344455\r\n");
        } finally {
            server.stop(0);
        }
    }

    /** Against Smile ID's real sandbox, with a dummy key: 401 means the request format passed (415 means it didn't). Opt-in only. */
    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "SMILE_ID_REAL_FORMAT_CHECK", matches = "1")
    void smileIdsSandboxAcceptsTheFormat() {
        SmileIdProperties properties = new SmileIdProperties();
        properties.setPartnerId("8371");
        properties.setApiKey("not-a-real-key");
        properties.setSandbox(true);

        Throwable failure = org.assertj.core.api.Assertions.catchThrowable(() -> new SmileIdClient(properties).mintToken());

        assertThat(failure.getCause().getMessage()).startsWith("401");
    }

    private static String sign(String key, String message) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.isEmpty() ? new byte[1] : key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
    }
}
