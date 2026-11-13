package org.coryd.notification.gateway.whatsapp;

import org.coryd.notification.config.WhatsAppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Termii's template endpoint, mocked - no request leaves the test. */
class TermiiWhatsAppProviderTest {

    private static final String URL = "https://termii.test/api/send/template";

    private MockRestServiceServer server;
    private TermiiWhatsAppProvider provider;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://termii.test");
        server = MockRestServiceServer.bindTo(builder).build();
        WhatsAppProperties properties = new WhatsAppProperties();
        properties.getTermii().setApiKey("test-key");
        properties.getTermii().setDeviceId("device-1");
        provider = new TermiiWhatsAppProvider(builder.build(), properties);
    }

    @Test
    void sendsTheTemplatePayloadTermiiDocumentsAndKeepsTheMessageId() {
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"phone_number":"2348031234567","device_id":"device-1","template_id":"tpl-1",
                         "api_key":"test-key","data":{"otp":"123456"}}""", true))
                .andRespond(withSuccess("""
                        {"code":"ok","balance":10,"message_id":"301","message":"Successfully Sent","message_id_str":"301"}""",
                        MediaType.APPLICATION_JSON));

        WhatsAppSendResult result = provider.sendTemplate("2348031234567", "tpl-1", Map.of("otp", "123456"));

        assertThat(result.delivered()).isTrue();
        assertThat(result.providerMessageId()).isEqualTo("301");
        server.verify();
    }

    @Test
    void anHttpErrorIsAFailedResultNotAnException() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON).body("{\"message\":\"template not approved\"}"));

        WhatsAppSendResult result = provider.sendTemplate("2348031234567", "tpl-1", Map.of("otp", "1"));

        assertThat(result.delivered()).isFalse();
        assertThat(result.errorCode()).isEqualTo("HTTP_400");
    }

    @Test
    void aServerErrorIsAFailedResult() {
        server.expect(requestTo(URL)).andRespond(withServerError());

        assertThat(provider.sendTemplate("2348031234567", "tpl-1", Map.of()).errorCode()).isEqualTo("HTTP_500");
    }

    @Test
    void a200ThatIsNotOkIsAFailedResult() {
        server.expect(requestTo(URL)).andRespond(withSuccess("""
                {"code":"error","message":"Insufficient balance"}""", MediaType.APPLICATION_JSON));

        WhatsAppSendResult result = provider.sendTemplate("2348031234567", "tpl-1", Map.of());

        assertThat(result.delivered()).isFalse();
        assertThat(result.errorCode()).isEqualTo("error");
    }

    @Test
    void noDeviceIdMeansNoCall() {
        WhatsAppProperties properties = new WhatsAppProperties();
        properties.getTermii().setApiKey("test-key");
        RestClient.Builder builder = RestClient.builder().baseUrl("https://termii.test");
        MockRestServiceServer untouched = MockRestServiceServer.bindTo(builder).build();

        WhatsAppSendResult result = new TermiiWhatsAppProvider(builder.build(), properties)
                .sendTemplate("2348031234567", "tpl-1", Map.of());

        assertThat(result.errorCode()).isEqualTo("NO_DEVICE_ID");
        untouched.verify();
    }
}
