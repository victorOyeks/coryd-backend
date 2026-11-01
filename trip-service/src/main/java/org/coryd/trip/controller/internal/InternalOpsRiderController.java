package org.coryd.trip.controller.internal;

import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.coryd.common.response.PagedResponse;
import org.coryd.trip.dto.OpsRiderReceipt;
import org.coryd.trip.dto.OpsRiderTrip;
import org.coryd.trip.service.OpsRiderTripService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** ops-service's Riders Directory - a rider's trip history and receipts. Service-to-service only. */
@RestController
@RequestMapping("/internal/ops/riders")
@RequiredArgsConstructor
public class InternalOpsRiderController {

    private final OpsRiderTripService opsRiderTripService;

    @GetMapping("/{riderId}/trips")
    public ApiResponse<PagedResponse<OpsRiderTrip>> trips(@PathVariable String riderId,
                                                          @RequestParam(required = false) String search,
                                                          @RequestParam(defaultValue = "0") int page,
                                                          @RequestParam(defaultValue = "10") int size) {
        return ApiResponse.ok(opsRiderTripService.trips(riderId, search, page, size));
    }

    @GetMapping("/{riderId}/trips/{bookingId}/receipt")
    public ApiResponse<OpsRiderReceipt> receipt(@PathVariable String riderId, @PathVariable String bookingId) {
        return ApiResponse.ok(opsRiderTripService.receipt(riderId, bookingId));
    }
}
