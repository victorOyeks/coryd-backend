package org.coryd.ops.controller;

import lombok.RequiredArgsConstructor;
import org.coryd.common.enums.RiderDirectorySort;
import org.coryd.common.enums.RiderDirectoryStatus;
import org.coryd.common.response.ApiResponse;
import org.coryd.common.response.PagedResponse;
import org.coryd.ops.dto.RiderDirectoryCounts;
import org.coryd.ops.dto.RiderDirectoryItem;
import org.coryd.ops.dto.RiderProfileView;
import org.coryd.ops.dto.RiderReceipt;
import org.coryd.ops.dto.RiderTripItem;
import org.coryd.ops.service.RiderDirectoryService;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Riders Directory - every rider with their trips, reliability, rating, spend, reports and status; a rider's profile, trips and receipts. */
@RestController
@RequestMapping("/riders")
@RequiredArgsConstructor
public class RiderDirectoryController {

    private final RiderDirectoryService riderDirectoryService;

    /** status is omitted for the "All" tab. Newest sign-up first unless sorted. */
    @GetMapping
    public ApiResponse<PagedResponse<RiderDirectoryItem>> riders(@RequestParam(required = false) RiderDirectoryStatus status,
                                                                 @RequestParam(required = false) String search,
                                                                 @RequestParam(required = false) RiderDirectorySort sort,
                                                                 @RequestParam(required = false) Sort.Direction direction,
                                                                 @RequestParam(defaultValue = "0") int page,
                                                                 @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(riderDirectoryService.riders(status, search, sort, direction, page, size));
    }

    @GetMapping("/counts")
    public ApiResponse<RiderDirectoryCounts> counts(@RequestParam(required = false) String search) {
        return ApiResponse.ok(riderDirectoryService.counts(search));
    }

    @GetMapping("/{riderId}")
    public ApiResponse<RiderProfileView> rider(@PathVariable String riderId) {
        return ApiResponse.ok(riderDirectoryService.rider(riderId));
    }

    /** Trip History: paid trips, newest first. search matches the route, the driver, a date ("11 July") or a day ("wed"). */
    @GetMapping("/{riderId}/trips")
    public ApiResponse<PagedResponse<RiderTripItem>> trips(@PathVariable String riderId,
                                                           @RequestParam(required = false) String search,
                                                           @RequestParam(defaultValue = "0") int page,
                                                           @RequestParam(defaultValue = "10") int size) {
        return ApiResponse.ok(riderDirectoryService.trips(riderId, search, page, size));
    }

    /** Download receipt: the receipt's contents - the console renders it and saves the PDF. */
    @GetMapping("/{riderId}/trips/{bookingId}/receipt")
    public ApiResponse<RiderReceipt> receipt(@PathVariable String riderId, @PathVariable String bookingId) {
        return ApiResponse.ok(riderDirectoryService.receipt(riderId, bookingId));
    }
}
