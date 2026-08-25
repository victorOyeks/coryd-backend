package org.coryd.common.security;

import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

/**
 * Attaches the shared internal API key to every outgoing call on whichever RestClient this is
 * registered on - unconditionally, not just on paths the receiving service actually gates.
 * A *ServiceClient class typically mixes internal-only and mobile-facing target paths on the
 * same RestClient instance, so it's simpler and just as correct to always send the header and
 * let the receiving InternalApiKeyFilter decide whether a given path actually requires it.
 */
public class InternalApiKeyInterceptor implements ClientHttpRequestInterceptor {

    private final String apiKey;

    public InternalApiKeyInterceptor(String apiKey) {
        this.apiKey = apiKey;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
        request.getHeaders().add(InternalApiKeyFilter.HEADER, apiKey);
        return execution.execute(request, body);
    }
}
