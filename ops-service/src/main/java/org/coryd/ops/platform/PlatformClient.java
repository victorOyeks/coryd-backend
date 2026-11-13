package org.coryd.ops.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.coryd.common.security.JwtUtil;
import org.coryd.ops.client.DownstreamException;
import org.coryd.ops.client.OpsRestClientsAccess;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Phase 6 calls to the owning services, with the admin's own token (OpsRestClients): settings and reports in trip-service,
 * broadcasts and emailed reports in notification-service, the verification pipeline in profile-service, finance in
 * payment-service. Envelope in, envelope's data and message out; a refusal keeps its own status and message.
 */
@Component
public class PlatformClient {

    public enum Service {
        TRIP("trip-service"), PAYMENT("payment-service"), PROFILE("profile-service"), NOTIFICATION("notification-service"),
        IDENTITY("identity-service");

        final String label;

        Service(String label) {
            this.label = label;
        }
    }

    public record Result(String message, JsonNode data) {
    }

    private final Map<Service, RestClient> clients;
    private final Map<Service, String> urls;
    private final ObjectMapper objectMapper;

    public PlatformClient(@Value("${services.trip.url}") String trip, @Value("${services.payment.url}") String payment,
                          @Value("${services.profile.url}") String profile, @Value("${services.notification.url}") String notification,
                          @Value("${services.identity.url}") String identity,
                          @Value("${services.internal.api-key}") String internalApiKey, JwtUtil jwtUtil, ObjectMapper objectMapper) {
        this.urls = Map.of(Service.TRIP, trip, Service.PAYMENT, payment, Service.PROFILE, profile, Service.NOTIFICATION, notification,
                Service.IDENTITY, identity);
        this.clients = Map.of(
                Service.TRIP, OpsRestClientsAccess.build(trip, internalApiKey, jwtUtil),
                Service.PAYMENT, OpsRestClientsAccess.build(payment, internalApiKey, jwtUtil),
                Service.PROFILE, OpsRestClientsAccess.build(profile, internalApiKey, jwtUtil),
                Service.NOTIFICATION, OpsRestClientsAccess.build(notification, internalApiKey, jwtUtil),
                Service.IDENTITY, OpsRestClientsAccess.build(identity, internalApiKey, jwtUtil));
        this.objectMapper = objectMapper;
    }

    public String url(Service service) {
        return urls.get(service);
    }

    public JsonNode get(Service service, String path, MultiValueMap<String, String> params) {
        return call(service, HttpMethod.GET, path, params, null).data();
    }

    public Result send(Service service, HttpMethod method, String path, MultiValueMap<String, String> params, Object body) {
        return call(service, method, path, params, body);
    }

    private Result call(Service service, HttpMethod method, String path, MultiValueMap<String, String> params, Object body) {
        try {
            RestClient.RequestBodySpec spec = clients.get(service).method(method)
                    .uri(b -> {
                        b.path(path);
                        if (params != null) {
                            b.queryParams(params);
                        }
                        return b.build();
                    });
            if (body != null) {
                spec.body(body);
            } else if (method != HttpMethod.GET) {
                spec.body(Map.of());
            }
            JsonNode envelope = spec.retrieve().body(JsonNode.class);
            return new Result(envelope != null && envelope.hasNonNull("message") ? envelope.get("message").asText() : null,
                    envelope == null ? null : envelope.get("data"));
        } catch (RestClientResponseException e) {
            throw new DownstreamException(e.getStatusCode(), message(service, e));
        } catch (ResourceAccessException e) {
            throw new DownstreamException(HttpStatus.BAD_GATEWAY, service.label + " is unavailable right now - try again shortly");
        }
    }

    /** Text (e.g. an HTML report preview), as the owning service rendered it. */
    public String text(Service service, String path, MultiValueMap<String, String> params) {
        try {
            return clients.get(service).get().uri(b -> b.path(path).queryParams(params).build()).retrieve().body(String.class);
        } catch (RestClientResponseException e) {
            throw new DownstreamException(e.getStatusCode(), message(service, e));
        } catch (ResourceAccessException e) {
            throw new DownstreamException(HttpStatus.BAD_GATEWAY, service.label + " is unavailable right now - try again shortly");
        }
    }

    /**
     * Streams a file from the owning service into out without holding it in memory; headers(name, value) gets the
     * upstream's report headers first. A refusal before any byte is relayed like any other.
     */
    public void stream(Service service, String path, MultiValueMap<String, String> params, BiConsumer<String, String> headers,
                       java.util.function.Supplier<OutputStream> body) {
        try {
            clients.get(service).get().uri(b -> b.path(path).queryParams(params).build())
                    .exchange((request, response) -> {
                        if (response.getStatusCode().isError()) {
                            String error = new String(response.getBody().readAllBytes());
                            throw new DownstreamException(response.getStatusCode(), messageFrom(service, response.getStatusCode().value(), error));
                        }
                        response.getHeaders().forEach((name, values) -> {
                            if (name.equalsIgnoreCase("Content-Disposition") || name.toLowerCase().startsWith("x-report-")) {
                                values.forEach(v -> headers.accept(name, v));
                            }
                        });
                        OutputStream out = body.get();
                        try (InputStream in = response.getBody()) {
                            in.transferTo(out);
                        }
                        out.flush();
                        return null;
                    });
        } catch (ResourceAccessException e) {
            throw new DownstreamException(HttpStatus.BAD_GATEWAY, service.label + " is unavailable right now - try again shortly");
        } catch (DownstreamException e) {
            throw e;
        } catch (RuntimeException e) {
            if (e.getCause() instanceof IOException) {
                throw new DownstreamException(HttpStatus.BAD_GATEWAY, service.label + " stopped sending the file part-way");
            }
            throw e;
        }
    }

    private String message(Service service, RestClientResponseException e) {
        return messageFrom(service, e.getStatusCode().value(), e.getResponseBodyAsString());
    }

    private String messageFrom(Service service, int status, String body) {
        try {
            JsonNode node = objectMapper.readTree(body);
            if (node != null && node.hasNonNull("message")) {
                return node.get("message").asText();
            }
        } catch (Exception ignored) {
            // not our envelope
        }
        return service.label + " returned " + status;
    }
}
