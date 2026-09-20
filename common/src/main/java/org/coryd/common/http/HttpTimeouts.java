package org.coryd.common.http;

import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * The one place every outbound HTTP client in CoRyd gets its timeouts from.
 *
 * <p>A plain {@code RestClient.create(url)} or {@code RestClient.builder()} has <em>no</em> connect
 * or read timeout - the underlying factory waits forever. That is not a theoretical problem: a
 * single hung call to a third party (Termii, Paystack, Google Maps) parks the calling thread for
 * good, and enough of those parks every thread in whichever pool made the call. When that pool is
 * the one behind {@code @Async} notification dispatch, the service keeps reporting healthy, keeps
 * committing Kafka offsets, and quietly stops delivering anything - the failure mode that took OTP
 * delivery down and only cleared on a restart.
 *
 * <p>Use {@link #builder()} instead of {@code RestClient.builder()} everywhere. The defaults suit
 * service-to-service calls on the same host; pass explicit durations for a slower third party.
 */
public final class HttpTimeouts {

    /** Generous enough for a cold TCP+TLS handshake to a third party, short enough to fail fast. */
    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);

    /** Covers a slow-but-alive upstream without ever becoming an indefinite park. */
    public static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(10);

    private HttpTimeouts() {
    }

    public static ClientHttpRequestFactory requestFactory() {
        return requestFactory(DEFAULT_CONNECT_TIMEOUT, DEFAULT_READ_TIMEOUT);
    }

    public static ClientHttpRequestFactory requestFactory(Duration connectTimeout, Duration readTimeout) {
        return ClientHttpRequestFactories.get(ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(connectTimeout)
                .withReadTimeout(readTimeout));
    }

    /** Drop-in replacement for {@code RestClient.builder()} that is never allowed to hang. */
    public static RestClient.Builder builder() {
        return RestClient.builder().requestFactory(requestFactory());
    }

    public static RestClient.Builder builder(Duration connectTimeout, Duration readTimeout) {
        return RestClient.builder().requestFactory(requestFactory(connectTimeout, readTimeout));
    }

    /** Drop-in replacement for {@code RestClient.create(baseUrl)}. */
    public static RestClient create(String baseUrl) {
        return builder().baseUrl(baseUrl).build();
    }
}
