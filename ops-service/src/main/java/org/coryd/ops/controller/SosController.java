package org.coryd.ops.controller;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.coryd.common.response.PagedResponse;
import org.coryd.ops.client.TripServiceClient;
import org.coryd.ops.dto.SosListItem;
import org.coryd.ops.service.SosOpsService;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/sos")
@RequiredArgsConstructor
public class SosController {

    private final SosOpsService sosOpsService;
    private final TripServiceClient tripServiceClient;

    /** Every SOS, newest first - caller and driver with phone numbers, the trip, and where it was pressed. */
    @GetMapping
    public ApiResponse<PagedResponse<SosListItem>> sos(@RequestParam(defaultValue = "0") int page,
                                                       @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(sosOpsService.sos(PageRequest.of(page, Math.min(size, 50))));
    }

    /** "Are you OK?" checks raised on trips (long stop, off route, signal lost), newest first, with each answer. */
    @GetMapping("/safety-alerts")
    public ApiResponse<JsonNode> safetyAlerts(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(tripServiceClient.safetyAlerts(page, Math.min(size, 50)));
    }
}
