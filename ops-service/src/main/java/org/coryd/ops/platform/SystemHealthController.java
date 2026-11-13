package org.coryd.ops.platform;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** System & job health - docs/ops-console/system-health.md. */
@RestController
@RequiredArgsConstructor
public class SystemHealthController {

    private final SystemHealthService service;

    /** hours: the window for provider failure counts (default 24). */
    @GetMapping("/system/health")
    public ApiResponse<JsonNode> health(@RequestParam(defaultValue = "24") int hours) {
        return ApiResponse.ok(service.health(hours));
    }
}
