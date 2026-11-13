package org.coryd.ops.platform;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.springframework.http.HttpMethod;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Broadcasts (docs/ops-console/broadcasts.md) - composed, sent and recorded by notification-service. */
@RestController
@RequestMapping("/broadcasts")
@RequiredArgsConstructor
public class BroadcastController {

    private static final String BASE = "/internal/ops/broadcasts";

    private final PlatformClient client;

    @PostMapping("/preview")
    public ApiResponse<JsonNode> preview(@RequestBody(required = false) PlatformRequests.Broadcast body) {
        if (body == null) {
            throw new org.coryd.common.exception.BadRequestException("Send the message, its channels and audience");
        }
        return SettingsController.relay(client.send(PlatformClient.Service.NOTIFICATION, HttpMethod.POST, BASE + "/preview", null,
                wire(body.checked(false))));
    }

    @PostMapping
    public ApiResponse<JsonNode> create(@RequestBody(required = false) PlatformRequests.Broadcast body) {
        if (body == null) {
            throw new org.coryd.common.exception.BadRequestException("Send the message, its channels and audience");
        }
        return SettingsController.relay(client.send(PlatformClient.Service.NOTIFICATION, HttpMethod.POST, BASE, null, wire(body.checked(true))));
    }

    @GetMapping
    public ApiResponse<JsonNode> list(@RequestParam(required = false) List<String> status, @RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(client.get(PlatformClient.Service.NOTIFICATION, BASE,
                Params.of().put("status", status).put("page", page).put("size", size).build()));
    }

    @GetMapping("/{id}")
    public ApiResponse<JsonNode> detail(@PathVariable String id) {
        return ApiResponse.ok(client.get(PlatformClient.Service.NOTIFICATION, BASE + "/" + id, null));
    }

    @GetMapping("/{id}/recipients")
    public ApiResponse<JsonNode> recipients(@PathVariable String id, @RequestParam(required = false) String status,
                                            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.ok(client.get(PlatformClient.Service.NOTIFICATION, BASE + "/" + id + "/recipients",
                Params.of().put("status", status).put("page", page).put("size", size).build()));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<JsonNode> cancel(@PathVariable String id, @RequestBody(required = false) PlatformRequests.Reason body) {
        PlatformRequests.requireReason(body == null ? null : body.reason());
        return SettingsController.relay(client.send(PlatformClient.Service.NOTIFICATION, HttpMethod.POST, BASE + "/" + id + "/cancel", null, body));
    }

    /** sendAt as ISO-8601 on the wire. */
    static java.util.Map<String, Object> wire(PlatformRequests.Broadcast b) {
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("title", b.title());
        out.put("body", b.body());
        out.put("channels", b.channels());
        out.put("audience", b.audience());
        out.put("urgent", b.urgent());
        out.put("sendAt", b.sendAt() == null ? null : b.sendAt().toString());
        out.put("reason", b.reason());
        return out;
    }
}
