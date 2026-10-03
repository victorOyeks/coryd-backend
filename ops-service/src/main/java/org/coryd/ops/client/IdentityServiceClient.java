package org.coryd.ops.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.coryd.common.security.JwtUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Collection;
import java.util.Map;

/** Phone numbers live only in identity-service. */
@Component
public class IdentityServiceClient {

    private static final String SERVICE = "identity-service";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public IdentityServiceClient(@Value("${services.identity.url}") String identityServiceUrl,
                                 @Value("${services.internal.api-key}") String internalApiKey,
                                 JwtUtil jwtUtil, ObjectMapper objectMapper) {
        this.restClient = OpsRestClients.build(identityServiceUrl, internalApiKey, jwtUtil);
        this.objectMapper = objectMapper;
    }

    public Map<String, Contact> contacts(Collection<String> userIds) {
        return DownstreamCalls.batch(SERVICE, objectMapper, userIds, Contact.class, Contact::userId, ids -> restClient.get()
                .uri(b -> b.path("/internal/users/contacts").queryParam("userIds", ids).build())
                .retrieve()
                .body(JsonNode.class));
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Contact(String userId, String fullName, String phone) {
    }
}
