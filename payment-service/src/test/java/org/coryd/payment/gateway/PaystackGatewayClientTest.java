package org.coryd.payment.gateway;

import com.sun.net.httpserver.HttpServer;
import org.coryd.common.exception.BadRequestException;
import org.coryd.common.exception.ExternalServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Against a stand-in Paystack on a local port - real HTTP, no real Paystack. */
class PaystackGatewayClientTest {

    private HttpServer paystack;
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicReference<String> body = new AtomicReference<>("{}");
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final PaystackProperties properties = new PaystackProperties();
    private PaystackGatewayClient client;

    @BeforeEach
    void start() throws Exception {
        paystack = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        paystack.createContext("/", exchange -> {
            requests.add(exchange.getRequestURI() + " " + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.get(), response.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(response);
            }
        });
        paystack.start();
        properties.setBaseUrl("http://localhost:" + paystack.getAddress().getPort());
        properties.setSecretKey("sk_test_abc");
        client = new PaystackGatewayClient(properties);
    }

    @AfterEach
    void stop() {
        paystack.stop(0);
    }

    @Test
    void aTestModeLimitComesBackAsAReadable400NotA500() {
        status.set(429);
        body.set("{\"status\":false,\"message\":\"Test mode daily limit of 3 live bank resolves exceeded. Use test bank codes 001 or upgrade to live mode.\"}");

        assertThatThrownBy(() -> client.resolveAccountNumber("0123451994", "999992"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Test mode daily limit");
    }

    @Test
    void aPaystackOutageIsA502() {
        status.set(503);
        body.set("<html>down</html>");

        assertThatThrownBy(() -> client.resolveAccountNumber("0123451994", "999992"))
                .isInstanceOf(ExternalServiceException.class);
    }

    @Test
    void withATestKeyAndTestBankEveryBankCallUsesTheTestBank() {
        properties.setTestBankCode("001");
        body.set("{\"status\":true,\"data\":{\"account_number\":\"0123451994\",\"account_name\":\"TEST ACCOUNT\",\"subaccount_code\":\"ACCT_1\",\"id\":42}}");

        client.resolveAccountNumber("0123451994", "999992");
        PaystackGatewayClient.SubaccountResult subaccount = client.createSubaccount("TEST ACCOUNT", "0123451994", "999992", new BigDecimal("95.00"));

        assertThat(requests.get(0)).contains("bank_code=001").doesNotContain("999992");
        assertThat(requests.get(1)).contains("\"settlement_bank\":\"001\"").doesNotContain("999992");
        assertThat(subaccount.id()).isEqualTo("42");
    }

    @Test
    void withATestKeyEveryDriverGetsTheSharedTestSubaccountWithoutCallingPaystack() {
        properties.setTestSubaccountCode("ACCT_testshared");

        PaystackGatewayClient.SubaccountResult subaccount = client.createSubaccount("TEST ACCOUNT", "0123451994", "057", new BigDecimal("95.00"));

        assertThat(subaccount.code()).isEqualTo("ACCT_testshared");
        assertThat(requests).isEmpty();
    }

    @Test
    void aLiveKeyNeverUsesTheTestSubaccount() {
        properties.setTestSubaccountCode("ACCT_testshared");
        properties.setSecretKey("sk_live_abc");
        body.set("{\"status\":true,\"data\":{\"subaccount_code\":\"ACCT_real\",\"id\":7}}");

        PaystackGatewayClient.SubaccountResult subaccount = client.createSubaccount("ADA OBI", "0123451994", "057", new BigDecimal("95.00"));

        assertThat(subaccount.code()).isEqualTo("ACCT_real");
        assertThat(requests).hasSize(1);
    }

    @Test
    void aLiveKeyAlwaysUsesTheRealBank() {
        properties.setTestBankCode("001");
        properties.setSecretKey("sk_live_abc");
        body.set("{\"status\":true,\"data\":{\"account_number\":\"0123451994\",\"account_name\":\"ADA OBI\"}}");

        client.resolveAccountNumber("0123451994", "999992");

        assertThat(requests.get(0)).contains("bank_code=999992");
    }

    @Test
    void withoutATestBankTheRealBankIsUsed() {
        body.set("{\"status\":true,\"data\":{\"account_number\":\"0123451994\",\"account_name\":\"ADA OBI\"}}");

        client.resolveAccountNumber("0123451994", "999992");

        assertThat(requests.get(0)).contains("bank_code=999992");
    }
}
