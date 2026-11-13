package org.coryd.ops.platform;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.springframework.http.HttpMethod;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Platform settings (docs/ops-console/settings.md) - stored and audited by trip-service, read live by every service. */
@RestController
@RequestMapping("/settings")
@RequiredArgsConstructor
public class SettingsController {

    private static final String BASE = "/internal/ops/settings";

    private final PlatformClient client;

    @GetMapping
    public ApiResponse<JsonNode> list() {
        return ApiResponse.ok(client.get(PlatformClient.Service.TRIP, BASE, null));
    }

    @GetMapping("/history")
    public ApiResponse<JsonNode> allHistory(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(client.get(PlatformClient.Service.TRIP, BASE + "/history", Params.of().put("page", page).put("size", size).build()));
    }

    @GetMapping("/{key}")
    public ApiResponse<JsonNode> one(@PathVariable String key) {
        return ApiResponse.ok(client.get(PlatformClient.Service.TRIP, BASE + "/" + key, null));
    }

    @GetMapping("/{key}/history")
    public ApiResponse<JsonNode> history(@PathVariable String key, @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(client.get(PlatformClient.Service.TRIP, BASE + "/" + key + "/history",
                Params.of().put("page", page).put("size", size).build()));
    }

    @PutMapping("/{key}")
    public ApiResponse<JsonNode> update(@PathVariable String key, @RequestBody(required = false) PlatformRequests.SettingUpdate body) {
        if (body == null) {
            PlatformRequests.requireReason(null);
        }
        return relay(client.send(PlatformClient.Service.TRIP, HttpMethod.PUT, BASE + "/" + key, null, body.checked()));
    }

    @PostMapping("/{key}/cancel-scheduled")
    public ApiResponse<JsonNode> cancelScheduled(@PathVariable String key, @RequestBody(required = false) PlatformRequests.Reason body) {
        PlatformRequests.requireReason(body == null ? null : body.reason());
        return relay(client.send(PlatformClient.Service.TRIP, HttpMethod.POST, BASE + "/" + key + "/cancel-scheduled", null, body));
    }

    @PostMapping("/{key}/reset")
    public ApiResponse<JsonNode> reset(@PathVariable String key, @RequestBody(required = false) PlatformRequests.Reason body) {
        PlatformRequests.requireReason(body == null ? null : body.reason());
        return relay(client.send(PlatformClient.Service.TRIP, HttpMethod.POST, BASE + "/" + key + "/reset", null, body));
    }

    static ApiResponse<JsonNode> relay(PlatformClient.Result result) {
        return result.message() == null ? ApiResponse.ok(result.data()) : ApiResponse.ok(result.message(), result.data());
    }
}
