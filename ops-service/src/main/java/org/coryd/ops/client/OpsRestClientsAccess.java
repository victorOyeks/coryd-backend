package org.coryd.ops.client;

import org.coryd.common.security.JwtUtil;
import org.springframework.web.client.RestClient;

/** OpsRestClients for clients outside this package (phase 6's PlatformClient). */
public final class OpsRestClientsAccess {

    private OpsRestClientsAccess() {
    }

    public static RestClient build(String baseUrl, String internalApiKey, JwtUtil jwtUtil) {
        return OpsRestClients.build(baseUrl, internalApiKey, jwtUtil);
    }
}
