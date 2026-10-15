package org.coryd.profile.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.coryd.profile.config.PremblyProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** Against a stand-in Prembly on a real port, replaying the body Prembly actually sent for a malformed licence. */
class PremblyClientTest {

    private static final String MALFORMED_LICENCE_BODY = "{\"status\":false,\"response_code\":\"05\",\"message\":\"Invalid request data\","
            + "\"detail\":\"Invalid request data\",\"data\":{},\"meta\":{},"
            + "\"verification\":{\"status\":\"NOT-VERIFIED\",\"reference\":\"a2bda0c8\"},"
            + "\"errors\":{\"number\":[\"Drivers license number must start with a letter followed by 8 digits and end with 2 letters\"]}}";

    private HttpServer server;
    private String lastRequest;
    private String lastPath;
    private int status;
    private String body;
    private PremblyClient client;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            lastRequest = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            lastPath = exchange.getRequestURI().getPath();
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
        PremblyProperties properties = new PremblyProperties();
        properties.setBaseUrl("http://localhost:" + server.getAddress().getPort());
        properties.setApiKey("test");
        client = new PremblyClient(properties, new ObjectMapper());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void aRejectedLicenceNumberIsAnAnswerNotAnOutage() {
        status = 400;
        body = MALFORMED_LICENCE_BODY;

        PremblyClient.DriversLicenceResult result = client.verifyDriversLicence("SEEDID6", "Ifeoma", "Chukwu");

        assertThat(result).isNotNull();
        assertThat(result.status()).isFalse();
        assertThat(result.inputError()).startsWith("Drivers license number must start with a letter");
        assertThat(result.reference()).isEqualTo("a2bda0c8");
    }

    @Test
    void aServerErrorIsStillAnOutage() {
        status = 500;
        body = "upstream exploded";

        assertThat(client.verifyDriversLicence("AAD2320823198", "Test", "Test")).isNull();
    }

    @Test
    void aNormalNoRecordAnswerHasNoInputError() {
        status = 200;
        body = "{\"status\":false,\"detail\":\"No record found\",\"verification\":{\"reference\":\"r-1\"}}";

        PremblyClient.DriversLicenceResult result = client.verifyDriversLicence("AAD2320823198", "Test", "Test");

        assertThat(result.status()).isFalse();
        assertThat(result.inputError()).isNull();
    }

    /** Prembly's own sample for NIN Basic (docs.prembly.com/docs/national-id-basic-1) - names, no photo. */
    @Test
    void ninBasicReadsTheNamesOnTheRecord() {
        status = 200;
        body = "{\"status\":true,\"response_code\":\"00\",\"detail\":\"National Identity Number (NIN) - Basic verification successful\","
                + "\"data\":{\"nin_suspension_status\":false,\"birthdate\":\"23-05-1999\",\"firstname\":\"GRACE\",\"surname\":\"AMANDA\","
                + "\"middlename\":\"CHIMAMANDA\",\"nin\":\"56182742701\",\"photo\":\"\",\"gender\":\"f\"},\"meta\":{},"
                + "\"verification\":{\"status\":\"VERIFIED\",\"reference\":\"a8d3c167\"},"
                + "\"nin_data\":{\"firstname\":\"GRACE\",\"surname\":\"AMANDA\",\"nin\":\"56182742701\",\"photo\":\"\"}}";

        PremblyClient.NinResult result = client.verifyNinBasic("56182742701");

        assertThat(lastPath).isEqualTo("/verification/vnin-basic");
        assertThat(lastRequest).isEqualTo("{\"number\":\"56182742701\"}");
        assertThat(result.status()).isTrue();
        assertThat(result.firstName()).isEqualTo("GRACE");
        assertThat(result.lastName()).isEqualTo("AMANDA");
        assertThat(result.faceMatched()).as("Basic has no face match").isNull();
        assertThat(result.reference()).isEqualTo("a8d3c167");
    }

    /** Prembly's own sample for NIN with face (docs.prembly.com/docs/nin-with-face-copy): confidence 1 IS a match. */
    @Test
    void ninWithFaceSendsTheThresholdAndReadsTheMatch() {
        status = 200;
        body = "{\"status\":true,\"detail\":\"Verification Successfull\",\"response_code\":\"00\","
                + "\"face_data\":{\"status\":true,\"message\":\"Face Match\",\"confidence\":1,\"threshold\":90},"
                + "\"nin_data\":{\"firstname\":\"test\",\"surname\":\"test\",\"nin\":\"12345678909\"},"
                + "\"verification\":{\"reference\":\"f06fc1a5\"}}";

        PremblyClient.NinResult result = client.verifyNinWithFace("12345678909", "https://img/selfie.jpg", 90);

        assertThat(lastPath).isEqualTo("/verification/nin_w_face");
        assertThat(lastRequest).contains("\"image\":\"https://img/selfie.jpg\"").contains("\"face_confidence_threshold\":90");
        assertThat(result.faceMatched()).isTrue();
        assertThat(result.firstName()).isEqualTo("test");
        assertThat(result.lastName()).isEqualTo("test");
    }

    /** Prembly's own sample for Face Liveliness (docs.prembly.com/docs/face-livelinness-check). */
    @Test
    void livenessReadsTheConfidenceInPercent() {
        status = 200;
        body = "{\"status\":true,\"detail\":\"Liveliness Detected\",\"response_code\":\"00\","
                + "\"data\":{\"confidence\":0.9999910736083985,\"confidence_in_percentage\":99.99910736083984},"
                + "\"verification\":{\"status\":\"VERIFIED\",\"reference\":\"661\",\"verification_id\":661},\"endpoint_name\":\"Face Liveliness\"}";

        PremblyClient.LivenessResult result = client.checkLiveness("https://img/selfie.jpg");

        assertThat(lastPath).isEqualTo("/verification/biometrics/face/liveliness_check");
        assertThat(result.status()).isTrue();
        assertThat(result.confidencePercent()).isGreaterThan(99.9);
        assertThat(result.reference()).isEqualTo("661");
    }
}
