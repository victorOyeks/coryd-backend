package org.coryd.ops.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.coryd.common.audit.OpsAuditPage;
import org.coryd.common.audit.OpsAuditQuery;
import org.coryd.common.security.JwtUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GET /internal/ops/audit on each service that keeps ops actions - same filter (OpsAuditQuery) everywhere. A source that
 * fails fails the page (502 / its own status): an audit log quietly missing rows would be worse than an error.
 */
@Component
public class OpsAuditClient {

    public static final String IDENTITY = "IDENTITY";
    public static final String PROFILE = "PROFILE";
    public static final String TRIP = "TRIP";
    public static final String PAYMENT = "PAYMENT";

    private final Map<String, RestClient> clients = new LinkedHashMap<>();
    private final Map<String, String> serviceNames = Map.of(IDENTITY, "identity-service", PROFILE, "profile-service",
            TRIP, "trip-service", PAYMENT, "payment-service");
    private final ObjectMapper objectMapper;

    public OpsAuditClient(@Value("${services.identity.url}") String identityUrl,
                          @Value("${services.profile.url}") String profileUrl,
                          @Value("${services.trip.url}") String tripUrl,
                          @Value("${services.payment.url}") String paymentUrl,
                          @Value("${services.internal.api-key}") String internalApiKey,
                          JwtUtil jwtUtil, ObjectMapper objectMapper) {
        clients.put(IDENTITY, OpsRestClients.build(identityUrl, internalApiKey, jwtUtil));
        clients.put(PROFILE, OpsRestClients.build(profileUrl, internalApiKey, jwtUtil));
        clients.put(TRIP, OpsRestClients.build(tripUrl, internalApiKey, jwtUtil));
        clients.put(PAYMENT, OpsRestClients.build(paymentUrl, internalApiKey, jwtUtil));
        this.objectMapper = objectMapper;
    }

    public OpsAuditPage page(String source, OpsAuditQuery q) {
        OpsAuditPage page = DownstreamCalls.data(serviceNames.get(source), objectMapper, OpsAuditPage.class,
                () -> clients.get(source).get().uri(b -> uri(b, q)).retrieve().body(com.fasterxml.jackson.databind.JsonNode.class));
        return page == null ? new OpsAuditPage(List.of(), false) : page;
    }

    private static URI uri(UriBuilder b, OpsAuditQuery q) {
        b.path("/internal/ops/audit");
        Map<String, Object> vars = new LinkedHashMap<>();
        param(b, vars, "actorId", q.actorId());
        list(b, vars, "actions", q.actions());
        list(b, vars, "categories", q.categories());
        list(b, vars, "targetTypes", q.targetTypes());
        param(b, vars, "targetId", q.targetId());
        param(b, vars, "from", q.from());
        param(b, vars, "to", q.to());
        param(b, vars, "search", q.search());
        param(b, vars, "beforeAt", q.beforeAt());
        param(b, vars, "beforeId", q.beforeId());
        param(b, vars, "size", q.size());
        // values as URI variables, so they're fully encoded ("+" in a search term stays a plus)
        return b.build(vars);
    }

    private static void param(UriBuilder b, Map<String, Object> vars, String name, Object value) {
        if (value != null && !(value instanceof String s && s.isBlank())) {
            b.queryParam(name, "{" + name + "}");
            vars.put(name, String.valueOf(value));
        }
    }

    private static void list(UriBuilder b, Map<String, Object> vars, String name, List<?> values) {
        if (values != null && !values.isEmpty()) {
            param(b, vars, name, String.join(",", values.stream().map(String::valueOf).toList()));
        }
    }
}
