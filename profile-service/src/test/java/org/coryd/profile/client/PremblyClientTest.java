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
    private int status;
    private String body;
    private PremblyClient client;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
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
}
