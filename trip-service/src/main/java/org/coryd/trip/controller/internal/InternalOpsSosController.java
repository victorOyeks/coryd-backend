package org.coryd.trip.controller.internal;

import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.coryd.common.response.PagedResponse;
import org.coryd.trip.dto.OpsSosItem;
import org.coryd.trip.entity.TripSafetyAlert;
import org.coryd.trip.service.OpsSosQueryService;
import org.coryd.trip.service.TripSafetyService;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Service-to-service only (ops-service, for the Ops Console) - gated by InternalApiKeyFilter via the /internal/** prefix. */
@Hidden
@RestController
@RequestMapping("/internal/ops/sos")
@RequiredArgsConstructor
public class InternalOpsSosController {

    private final OpsSosQueryService opsSosQueryService;
    private final TripSafetyService tripSafetyService;

    @GetMapping
    public ApiResponse<PagedResponse<OpsSosItem>> sos(@RequestParam(defaultValue = "0") int page,
                                                      @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(opsSosQueryService.sos(PageRequest.of(page, Math.min(size, 50))));
    }

    /** "Are you OK?" checks raised on trips, newest first - with who answered what. */
    @GetMapping("/safety-alerts")
    public ApiResponse<PagedResponse<TripSafetyAlert>> safetyAlerts(@RequestParam(defaultValue = "0") int page,
                                                                    @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(PagedResponse.from(tripSafetyService.recent(PageRequest.of(page, Math.min(size, 50)))));
    }
}
