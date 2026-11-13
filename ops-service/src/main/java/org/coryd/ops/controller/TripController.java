package org.coryd.ops.controller;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.coryd.common.enums.OpsTripStatus;
import org.coryd.common.enums.RouteStatus;
import org.coryd.common.response.ApiResponse;
import org.coryd.common.response.PagedResponse;
import org.coryd.common.security.CoRydUserDetails;
import org.coryd.ops.client.TripServiceClient;
import org.coryd.ops.dto.CancelTripRequest;
import org.coryd.ops.dto.LiveTripsResponse;
import org.coryd.ops.dto.ResolveTripRequest;
import org.coryd.ops.dto.StuckTripItem;
import org.coryd.ops.dto.StuckTripOutcome;
import org.coryd.ops.dto.TripCountsResponse;
import org.coryd.ops.dto.TripDetailResponse;
import org.coryd.ops.dto.TripListItem;
import org.coryd.ops.dto.TripSort;
import org.coryd.ops.service.TripOpsService;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/** Trips: the table, counts, one trip, the Live Trips monitor, stuck trips, and cancelling or resolving a trip. */
@RestController
@RequestMapping("/trips")
@RequiredArgsConstructor
public class TripController {

    private final TripOpsService tripOpsService;
    private final TripServiceClient tripServiceClient;

    /** Trips table - status is omitted entirely for the "All" tab. from/to are Lagos days of the departure, inclusive. */
    @GetMapping
    public ApiResponse<PagedResponse<TripListItem>> trips(@RequestParam(required = false) OpsTripStatus status,
                                                          @RequestParam(required = false) List<RouteStatus> routeStatus,
                                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                          @RequestParam(required = false) String driverId,
                                                          @RequestParam(required = false) String riderId,
                                                          @RequestParam(required = false) String search,
                                                          @RequestParam(required = false) TripSort sort,
                                                          @RequestParam(required = false) Sort.Direction direction,
                                                          @RequestParam(defaultValue = "0") int page,
                                                          @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.max(1, Math.min(size, 50)));
        return ApiResponse.ok(tripOpsService.trips(
                new TripServiceClient.TripQuery(status, routeStatus, from, to, driverId, riderId, search, sort, direction), pageable));
    }

    /** The tab badges - they follow the table's filters, except status. */
    @GetMapping("/counts")
    public ApiResponse<TripCountsResponse> counts(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                  @RequestParam(required = false) String driverId,
                                                  @RequestParam(required = false) String riderId,
                                                  @RequestParam(required = false) String search) {
        return ApiResponse.ok(tripOpsService.counts(
                new TripServiceClient.TripQuery(null, null, from, to, driverId, riderId, search, null, null)));
    }

    /** Live Trips monitor - started trips plus those boarding (departing within 30 minutes). */
    @GetMapping("/live")
    public ApiResponse<LiveTripsResponse> live(@RequestParam(required = false) String search) {
        return ApiResponse.ok(tripOpsService.live(search));
    }

    /** Trips left in progress well past their expected arrival - ops completes or cancels each. */
    @GetMapping("/stuck")
    public ApiResponse<PagedResponse<StuckTripItem>> stuck(@RequestParam(defaultValue = "0") int page,
                                                           @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(tripOpsService.stuck(PageRequest.of(Math.max(page, 0), Math.max(1, Math.min(size, 50)))));
    }

    /** Drivers who keep moving trips riders have booked within the last hour before departure - most first. */
    @GetMapping("/time-changes/last-minute")
    public ApiResponse<JsonNode> lastMinuteTimeChanges(@RequestParam(defaultValue = "30") int days,
                                                       @RequestParam(defaultValue = "2") int minLastMinute) {
        return ApiResponse.ok(tripServiceClient.lastMinuteTimeChanges(Math.max(1, Math.min(days, 365)), Math.max(1, minLastMinute)));
    }

    /** The "View" page for one trip. */
    @GetMapping("/{routePostId}")
    public ApiResponse<TripDetailResponse> trip(@PathVariable String routePostId) {
        return ApiResponse.ok(tripOpsService.trip(routePostId));
    }

    /** Cancel the whole trip for the driver - every paid rider refunded in full. */
    @PostMapping("/{routePostId}/cancel")
    public ApiResponse<JsonNode> cancel(@AuthenticationPrincipal CoRydUserDetails admin, @PathVariable String routePostId,
                                        @Valid @RequestBody CancelTripRequest request) {
        return ApiResponse.ok("Trip cancelled", tripOpsService.cancelTrip(routePostId, request, admin));
    }

    /** Resolve a stuck trip - mark it completed or cancel it. */
    @PostMapping("/{routePostId}/resolve")
    public ApiResponse<JsonNode> resolve(@AuthenticationPrincipal CoRydUserDetails admin, @PathVariable String routePostId,
                                         @Valid @RequestBody ResolveTripRequest request) {
        return ApiResponse.ok(request.outcome() == StuckTripOutcome.COMPLETED ? "Trip marked completed" : "Trip cancelled",
                tripOpsService.resolve(routePostId, request, admin));
    }
}
