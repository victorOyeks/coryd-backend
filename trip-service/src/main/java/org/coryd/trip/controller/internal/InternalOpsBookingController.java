package org.coryd.trip.controller.internal;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.coryd.common.response.PagedResponse;
import org.coryd.trip.dto.OpsActionResult;
import org.coryd.trip.dto.OpsCancelRequest;
import org.coryd.trip.dto.OpsCancellationCounts;
import org.coryd.trip.dto.OpsCancellationFilter;
import org.coryd.trip.dto.OpsCancellationInfo;
import org.coryd.trip.entity.CancellationFault;
import org.coryd.trip.entity.CancellationLog;
import org.coryd.trip.entity.CancellationSource;
import org.coryd.trip.service.OpsTripActionService;
import org.coryd.trip.service.OpsTripQueryService;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** Ops Console booking actions and the Cancellations Log - ops-service only, behind InternalApiKeyFilter. */
@Hidden
@RestController
@RequestMapping("/internal/ops")
@RequiredArgsConstructor
public class InternalOpsBookingController {

    private final OpsTripActionService opsTripActionService;
    private final OpsTripQueryService opsTripQueryService;

    @PostMapping("/bookings/{bookingId}/cancel")
    public ApiResponse<OpsActionResult> cancel(@PathVariable String bookingId, @Valid @RequestBody OpsCancelRequest request) {
        return ApiResponse.ok("Booking cancelled", opsTripActionService.cancelBooking(bookingId, request));
    }

    @GetMapping("/cancellations")
    public ApiResponse<PagedResponse<OpsCancellationInfo>> cancellations(
            @RequestParam(required = false) CancellationSource source,
            @RequestParam(required = false) CancellationFault fault,
            @RequestParam(required = false) CancellationLog.Kind kind,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String driverId,
            @RequestParam(required = false) String riderId,
            @RequestParam(required = false) String routePostId,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        OpsCancellationFilter filter = new OpsCancellationFilter(source, fault, kind, from, to, driverId, riderId, routePostId, search);
        return ApiResponse.ok(opsTripQueryService.cancellations(filter, PageRequest.of(Math.max(page, 0), Math.max(1, Math.min(size, 50)))));
    }

    @GetMapping("/cancellations/counts")
    public ApiResponse<OpsCancellationCounts> cancellationCounts(
            @RequestParam(required = false) CancellationFault fault,
            @RequestParam(required = false) CancellationLog.Kind kind,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String driverId,
            @RequestParam(required = false) String riderId,
            @RequestParam(required = false) String routePostId,
            @RequestParam(required = false) String search) {
        return ApiResponse.ok(opsTripQueryService.cancellationCounts(
                new OpsCancellationFilter(null, fault, kind, from, to, driverId, riderId, routePostId, search)));
    }
}
