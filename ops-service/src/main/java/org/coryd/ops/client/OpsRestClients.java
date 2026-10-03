package org.coryd.ops.client;

import org.coryd.common.http.HttpTimeouts;
import org.coryd.common.security.JwtUtil;
import org.coryd.common.security.client.ForwardedAuthInterceptor;
import org.coryd.common.security.client.InternalApiKeyInterceptor;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

/** One way to build a client to another service: the ops admin's own token forwarded, the internal key
 * attached, and the JDK client - the default this Boot version picks (HttpURLConnection) can't send PATCH. */
final class OpsRestClients {

    private OpsRestClients() {
    }

    static RestClient build(String baseUrl, String internalApiKey, JwtUtil jwtUtil) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(HttpTimeouts.DEFAULT_CONNECT_TIMEOUT)
                .build());
        requestFactory.setReadTimeout(HttpTimeouts.DEFAULT_READ_TIMEOUT);
        return RestClient.builder()
                .requestFactory(requestFactory)
                .baseUrl(baseUrl)
                .requestInterceptor(new ForwardedAuthInterceptor(jwtUtil))
                .requestInterceptor(new InternalApiKeyInterceptor(internalApiKey))
                .build();
    }
}
