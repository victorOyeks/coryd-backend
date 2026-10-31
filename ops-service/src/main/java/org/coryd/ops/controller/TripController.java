package org.coryd.ops.controller;

import lombok.RequiredArgsConstructor;
import org.coryd.common.enums.OpsTripStatus;
import org.coryd.common.response.ApiResponse;
import org.coryd.common.response.PagedResponse;
import org.coryd.ops.dto.LiveTripsResponse;
import org.coryd.ops.dto.TripCountsResponse;
import org.coryd.ops.dto.TripDetailResponse;
import org.coryd.ops.dto.TripListItem;
import org.coryd.ops.service.TripOpsService;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/trips")
@RequiredArgsConstructor
public class TripController {

    private final TripOpsService tripOpsService;
    private final org.coryd.ops.client.TripServiceClient tripServiceClient;

    /** Trips table - status is omitted entirely for the "All" tab. */
    @GetMapping
    public ApiResponse<PagedResponse<TripListItem>> trips(@RequestParam(required = false) OpsTripStatus status,
                                                          @RequestParam(required = false) String search,
                                                          @RequestParam(defaultValue = "0") int page,
                                                          @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, Math.min(size, 50));
        return ApiResponse.ok(tripOpsService.trips(status, search, pageable));
    }

    /** The tab badges - they follow the same search as the table. */
    @GetMapping("/counts")
    public ApiResponse<TripCountsResponse> counts(@RequestParam(required = false) String search) {
        return ApiResponse.ok(tripOpsService.counts(search));
    }

    /** Live Trips monitor - started trips plus those boarding (departing within 30 minutes). */
    @GetMapping("/live")
    public ApiResponse<LiveTripsResponse> live(@RequestParam(required = false) String search) {
        return ApiResponse.ok(tripOpsService.live(search));
    }

    /** The "View" page for one trip. */
    /** Drivers who keep moving trips riders have booked within the last hour before departure - most first. */
    @GetMapping("/time-changes/last-minute")
    public ApiResponse<com.fasterxml.jackson.databind.JsonNode> lastMinuteTimeChanges(@RequestParam(defaultValue = "30") int days,
                                                                                      @RequestParam(defaultValue = "2") int minLastMinute) {
        return ApiResponse.ok(tripServiceClient.lastMinuteTimeChanges(Math.max(1, Math.min(days, 365)), Math.max(1, minLastMinute)));
    }

    @GetMapping("/{routePostId}")
    public ApiResponse<TripDetailResponse> trip(@PathVariable String routePostId) {
        return ApiResponse.ok(tripOpsService.trip(routePostId));
    }
}
