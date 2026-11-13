package org.coryd.trip.controller.internal;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.coryd.common.enums.OpsTripStatus;
import org.coryd.common.enums.RouteStatus;
import org.coryd.common.response.ApiResponse;
import org.coryd.common.response.PagedResponse;
import org.coryd.trip.dto.OpsActionResult;
import org.coryd.trip.dto.OpsCancelRequest;
import org.coryd.trip.dto.OpsLiveTripItem;
import org.coryd.trip.dto.OpsResolveRequest;
import org.coryd.trip.dto.OpsStuckTripItem;
import org.coryd.trip.dto.OpsTripCountsResponse;
import org.coryd.trip.dto.OpsTripDetailResponse;
import org.coryd.trip.dto.OpsTripFilter;
import org.coryd.trip.dto.OpsTripItem;
import org.coryd.trip.dto.OpsTripSort;
import org.coryd.trip.service.OpsTripActionService;
import org.coryd.trip.service.OpsTripQueryService;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/** Service-to-service only (ops-service, for the Ops Console) - gated by InternalApiKeyFilter via the /internal/** prefix. */
@Hidden
@RestController
@RequestMapping("/internal/ops/trips")
@RequiredArgsConstructor
public class InternalOpsTripController {

    private final OpsTripQueryService opsTripQueryService;
    private final OpsTripActionService opsTripActionService;
    private final org.coryd.trip.service.RouteTimeChangeService routeTimeChangeService;

    /** status is omitted entirely for the "All" tab. */
    @GetMapping
    public ApiResponse<PagedResponse<OpsTripItem>> trips(@RequestParam(required = false) OpsTripStatus status,
                                                         @RequestParam(required = false) List<RouteStatus> routeStatus,
                                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                         @RequestParam(required = false) String driverId,
                                                         @RequestParam(required = false) String riderId,
                                                         @RequestParam(required = false) String search,
                                                         @RequestParam(required = false) OpsTripSort sort,
                                                         @RequestParam(required = false) Sort.Direction direction,
                                                         @RequestParam(defaultValue = "0") int page,
                                                         @RequestParam(defaultValue = "20") int size) {
        OpsTripFilter filter = new OpsTripFilter(status, routeStatus, from, to, driverId, riderId, search);
        return ApiResponse.ok(opsTripQueryService.trips(filter, sort, direction, PageRequest.of(Math.max(page, 0), clamp(size))));
    }

    @GetMapping("/counts")
    public ApiResponse<OpsTripCountsResponse> counts(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                     @RequestParam(required = false) String driverId,
                                                     @RequestParam(required = false) String riderId,
                                                     @RequestParam(required = false) String search) {
        return ApiResponse.ok(opsTripQueryService.tripCounts(new OpsTripFilter(null, null, from, to, driverId, riderId, search)));
    }

    @GetMapping("/live")
    public ApiResponse<List<OpsLiveTripItem>> live(@RequestParam(required = false) String search) {
        return ApiResponse.ok(opsTripQueryService.live(search));
    }

    /** In-progress trips past their expected arrival plus the configured grace - longest overdue first. */
    @GetMapping("/stuck")
    public ApiResponse<PagedResponse<OpsStuckTripItem>> stuck(@RequestParam(defaultValue = "0") int page,
                                                              @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(opsTripQueryService.stuck(PageRequest.of(Math.max(page, 0), clamp(size))));
    }

    /** Drivers who keep moving trips riders have booked at the last minute - most first. */
    @GetMapping("/time-changes/last-minute")
    public ApiResponse<List<org.coryd.trip.dto.OpsTimeChangeDriverItem>> lastMinuteTimeChanges(
            @RequestParam(defaultValue = "30") int days, @RequestParam(defaultValue = "2") int minLastMinute) {
        return ApiResponse.ok(routeTimeChangeService.frequentLastMinuteChangers(days, minLastMinute));
    }

    @GetMapping("/{routePostId}")
    public ApiResponse<OpsTripDetailResponse> trip(@PathVariable String routePostId) {
        return ApiResponse.ok(opsTripQueryService.trip(routePostId));
    }

    @PostMapping("/{routePostId}/cancel")
    public ApiResponse<OpsActionResult> cancel(@PathVariable String routePostId, @Valid @RequestBody OpsCancelRequest request) {
        return ApiResponse.ok("Trip cancelled", opsTripActionService.cancelTrip(routePostId, request));
    }

    @PostMapping("/{routePostId}/resolve")
    public ApiResponse<OpsActionResult> resolve(@PathVariable String routePostId, @Valid @RequestBody OpsResolveRequest request) {
        return ApiResponse.ok(request.outcome() == OpsResolveRequest.Outcome.COMPLETED ? "Trip marked completed" : "Trip cancelled",
                opsTripActionService.resolve(routePostId, request));
    }

    private static int clamp(int size) {
        return Math.max(1, Math.min(size, 50));
    }
}
