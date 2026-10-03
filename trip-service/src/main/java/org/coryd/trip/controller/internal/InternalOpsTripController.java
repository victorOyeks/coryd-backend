package org.coryd.trip.controller.internal;

import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.coryd.common.enums.OpsTripStatus;
import org.coryd.common.response.ApiResponse;
import org.coryd.common.response.PagedResponse;
import org.coryd.trip.dto.OpsLiveTripItem;
import org.coryd.trip.dto.OpsTripCountsResponse;
import org.coryd.trip.dto.OpsTripDetailResponse;
import org.coryd.trip.dto.OpsTripItem;
import org.coryd.trip.service.OpsTripQueryService;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Service-to-service only (ops-service, for the Ops Console) - gated by InternalApiKeyFilter via the /internal/** prefix. */
@Hidden
@RestController
@RequestMapping("/internal/ops/trips")
@RequiredArgsConstructor
public class InternalOpsTripController {

    private final OpsTripQueryService opsTripQueryService;

    /** status is omitted entirely for the "All" tab. */
    @GetMapping
    public ApiResponse<PagedResponse<OpsTripItem>> trips(@RequestParam(required = false) OpsTripStatus status,
                                                         @RequestParam(required = false) String search,
                                                         @RequestParam(defaultValue = "0") int page,
                                                         @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, Math.min(size, 50));
        return ApiResponse.ok(opsTripQueryService.trips(status, search, pageable));
    }

    @GetMapping("/counts")
    public ApiResponse<OpsTripCountsResponse> counts(@RequestParam(required = false) String search) {
        return ApiResponse.ok(opsTripQueryService.counts(search));
    }

    @GetMapping("/live")
    public ApiResponse<List<OpsLiveTripItem>> live(@RequestParam(required = false) String search) {
        return ApiResponse.ok(opsTripQueryService.live(search));
    }

    @GetMapping("/{routePostId}")
    public ApiResponse<OpsTripDetailResponse> trip(@PathVariable String routePostId) {
        return ApiResponse.ok(opsTripQueryService.trip(routePostId));
    }
}
