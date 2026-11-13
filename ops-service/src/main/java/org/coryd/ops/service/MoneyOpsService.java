package org.coryd.ops.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.exception.BadRequestException;
import org.coryd.common.security.CoRydUserDetails;
import org.coryd.ops.client.DownstreamException;
import org.coryd.ops.client.IdentityServiceClient;
import org.coryd.ops.client.OpsMoneyClient;
import org.coryd.ops.client.TripServiceClient;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Ops Console money (Phase 4). payment-service owns every figure and every action - and checks the admin, the reason, the
 * amount and the two-person rule itself; this service passes the console's request on with the admin's token and adds
 * what payment-service doesn't know: people's names and phones (one identity-service call per page), the console's short
 * references (B-/R-/D-/T-), the trip a booking belongs to, and trip-service's pricing monitor.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MoneyOpsService {

    private static final Pattern TRIP_REF = Pattern.compile("^[Tt]-[0-9A-Fa-f]{6}$");

    private final OpsMoneyClient moneyClient;
    private final IdentityServiceClient identityServiceClient;
    private final TripServiceClient tripServiceClient;

    public JsonNode get(String path, MultiValueMap<String, String> params) {
        return enrich(moneyClient.get(path, params));
    }

    public OpsMoneyClient.Result post(String path, Object body) {
        OpsMoneyClient.Result result = moneyClient.post(path, body);
        return new OpsMoneyClient.Result(result.message(), enrich(result.data()));
    }

    /** A trip filter ("T-2F2F53" or a full id) becomes the trip's bookings - payment-service knows bookings, not trips. */
    public void resolveTrip(String trip, MultiValueMap<String, String> params) {
        if (trip == null || trip.isBlank()) {
            return;
        }
        String value = trip.trim();
        List<String> routeIds = new ArrayList<>();
        if (TRIP_REF.matcher(value).matches()) {
            String prefix = value.substring(2).toLowerCase(Locale.ROOT);
            tripServiceClient.trips(new TripServiceClient.TripQuery(null, null, null, null, null, null, value, null, null), 0, 50)
                    .content().stream().map(TripServiceClient.TripRow::routePostId)
                    .filter(id -> id != null && id.replace("-", "").toLowerCase(Locale.ROOT).startsWith(prefix))
                    .forEach(routeIds::add);
        } else {
            routeIds.add(value);
        }
        Set<String> bookingIds = new LinkedHashSet<>();
        for (String routeId : routeIds) {
            try {
                TripServiceClient.TripDetail detail = tripServiceClient.trip(routeId);
                if (detail != null && detail.passengers() != null) {
                    detail.passengers().forEach(p -> bookingIds.add(p.bookingId()));
                }
            } catch (DownstreamException e) {
                if (e.getStatus().value() != 404) {
                    throw e;
                }
            }
        }
        // No bookings matches nothing - an empty list, not "no filter".
        params.put("bookingIds", bookingIds.isEmpty() ? List.of("") : new ArrayList<>(bookingIds));
    }

    /** A transaction's detail, with the trip its booking belongs to when there is one. */
    public JsonNode transaction(String type, String id) {
        JsonNode detail = get("/transactions/" + type + "/" + id, new LinkedMultiValueMap<>());
        JsonNode row = detail == null ? null : detail.get("transaction");
        if (row instanceof ObjectNode tx && tx.hasNonNull("bookingId") && tx.hasNonNull("riderId") && detail instanceof ObjectNode d) {
            try {
                TripServiceClient.RiderReceipt receipt = tripServiceClient.riderReceipt(tx.get("riderId").asText(), tx.get("bookingId").asText());
                if (receipt != null) {
                    ObjectNode trip = d.putObject("trip");
                    trip.put("id", receipt.routePostId());
                    trip.put("ref", TripOpsService.ref("T", receipt.routePostId()));
                    trip.put("originLabel", receipt.originLabel());
                    trip.put("destinationLabel", receipt.destinationLabel());
                    trip.put("departureTime", receipt.departureTime() == null ? null : receipt.departureTime().toString());
                    trip.put("bookingStatus", receipt.status() == null ? null : receipt.status().name());
                    trip.put("seatCount", receipt.seatCount());
                    trip.put("fareTotal", receipt.fareTotal());
                }
            } catch (Exception e) {
                log.warn("No trip for booking {}: {}", tx.get("bookingId").asText(), e.getMessage());
            }
        }
        return detail;
    }

    public JsonNode pricing(MultiValueMap<String, String> params) {
        return enrich(moneyClient.pricing(params));
    }

    public static String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BadRequestException("reason: a reason is required");
        }
        return reason.trim();
    }

    public static String actor(CoRydUserDetails admin) {
        return admin == null ? null : admin.getUserId();
    }

    // ---- enrichment ------------------------------------------------------------------------------------------------

    /**
     * Every object with a riderId / driverId / bookingId / routePostId gets the console's reference beside it, and a
     * rider / driver object with name and phone - one batched identity-service lookup for the whole response.
     */
    JsonNode enrich(JsonNode root) {
        if (root == null) {
            return null;
        }
        Set<String> people = new LinkedHashSet<>();
        collect(root, people);
        Map<String, IdentityServiceClient.Contact> contacts = people.isEmpty() ? Map.of() : identityServiceClient.contacts(people);
        annotate(root, contacts);
        return root;
    }

    private static void collect(JsonNode node, Set<String> people) {
        if (node.isObject()) {
            for (String field : List.of("riderId", "driverId")) {
                if (node.hasNonNull(field) && node.get(field).isTextual()) {
                    people.add(node.get(field).asText());
                }
            }
            node.forEach(child -> collect(child, people));
        } else if (node.isArray()) {
            node.forEach(child -> collect(child, people));
        }
    }

    private static void annotate(JsonNode node, Map<String, IdentityServiceClient.Contact> contacts) {
        if (node instanceof ObjectNode object) {
            List<String> fields = new ArrayList<>();
            object.fieldNames().forEachRemaining(fields::add);
            for (String field : fields) {
                annotate(object.get(field), contacts);
            }
            person(object, "riderId", "rider", "R", contacts);
            person(object, "driverId", "driver", "D", contacts);
            if (object.hasNonNull("bookingId") && object.get("bookingId").isTextual() && !object.has("bookingRef")) {
                object.put("bookingRef", TripOpsService.ref("B", object.get("bookingId").asText()));
            }
            if (object.hasNonNull("routePostId") && object.get("routePostId").isTextual() && !object.has("tripRef")) {
                object.put("tripRef", TripOpsService.ref("T", object.get("routePostId").asText()));
            }
        } else if (node != null && node.isArray()) {
            node.forEach(child -> annotate(child, contacts));
        }
    }

    private static void person(ObjectNode object, String idField, String name, String prefix, Map<String, IdentityServiceClient.Contact> contacts) {
        if (!object.hasNonNull(idField) || !object.get(idField).isTextual() || object.has(name)) {
            return;
        }
        String id = object.get(idField).asText();
        IdentityServiceClient.Contact contact = contacts.get(id);
        ObjectNode person = object.putObject(name);
        person.put("id", id);
        person.put("ref", TripOpsService.ref(prefix, id));
        person.put("name", contact == null ? null : contact.fullName());
        person.put("phone", contact == null ? null : contact.phone());
    }
}
