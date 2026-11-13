package org.coryd.payment.config;

import jakarta.servlet.FilterChain;
import org.coryd.common.security.InternalOnlyPaths;
import org.coryd.common.security.filter.InternalApiKeyFilter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * /internal/** is service-to-service only on every environment. The key filter used to exist only with the dev tools on,
 * which left /internal/** (payout recipients, stats, account-deletion purge, ops booking payments) open to any signed-in
 * user, a rider included, wherever the dev tools were off.
 */
class InternalPathsSecurityTest {

    private static final String KEY = "test-internal-key";
    private final InternalApiKeyFilter filter = new SecurityConfig(null, null, null)
            .internalApiKeyFilter(KEY);

    @Test
    void theKeyFilterIsNeverConditionalOnTheDevTools() throws Exception {
        var method = SecurityConfig.class.getMethod("internalApiKeyFilter", String.class);
        assertThat(method.getAnnotation(ConditionalOnProperty.class)).isNull();
    }

    @Test
    void anInternalPathWithoutTheKeyIsRefusedEvenWithAUserToken() throws Exception {
        MockHttpServletRequest request = request("/internal/ops/payments/bookings");
        request.addHeader("Authorization", "Bearer a-rider-token");
        assertThat(passes(request)).isFalse();
    }

    @Test
    void anInternalPathWithTheKeyGoesThrough() throws Exception {
        MockHttpServletRequest request = request("/internal/payout-recipients/d-1");
        request.addHeader(InternalApiKeyFilter.HEADER, KEY);
        assertThat(passes(request)).isTrue();
    }

    @Test
    void aWrongKeyIsRefused() throws Exception {
        MockHttpServletRequest request = request("/internal/stats");
        request.addHeader(InternalApiKeyFilter.HEADER, "not-the-key");
        assertThat(passes(request)).isFalse();
    }

    @Test
    void theAppsOwnPathsAreLeftToTheJwtRules() throws Exception {
        assertThat(passes(request("/payments/history"))).isTrue();
        assertThat(passes(request("/cards"))).isTrue();
        assertThat(List.of(InternalOnlyPaths.PAYMENT_SERVICE)).contains("/internal/**");
    }

    private static MockHttpServletRequest request(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setServletPath(path);
        return request;
    }

    private boolean passes(MockHttpServletRequest request) throws Exception {
        AtomicBoolean reached = new AtomicBoolean(false);
        FilterChain chain = (req, res) -> reached.set(true);
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        return reached.get();
    }
}
