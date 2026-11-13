package org.coryd.ops.controller;

import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.coryd.common.response.PagedResponse;
import org.coryd.ops.client.TripServiceClient;
import org.coryd.ops.dto.CancellationCounts;
import org.coryd.ops.dto.CancellationFault;
import org.coryd.ops.dto.CancellationItem;
import org.coryd.ops.dto.CancellationKind;
import org.coryd.ops.dto.CancellationSource;
import org.coryd.ops.service.TripOpsService;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** The Cancellations Log - every cancelled trip and paid seat, newest first. */
@RestController
@RequestMapping("/cancellations")
@RequiredArgsConstructor
public class CancellationController {

    private final TripOpsService tripOpsService;

    @GetMapping
    public ApiResponse<PagedResponse<CancellationItem>> cancellations(
            @RequestParam(required = false) CancellationSource source,
            @RequestParam(required = false) CancellationFault fault,
            @RequestParam(required = false) CancellationKind kind,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String driverId,
            @RequestParam(required = false) String riderId,
            @RequestParam(required = false) String routePostId,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(tripOpsService.cancellations(
                new TripServiceClient.CancellationQuery(source, fault, kind, from, to, driverId, riderId, routePostId, search),
                PageRequest.of(Math.max(page, 0), Math.max(1, Math.min(size, 50)))));
    }

    /** Tab badges by who cancelled - same filters as the list, except source. */
    @GetMapping("/counts")
    public ApiResponse<CancellationCounts> counts(
            @RequestParam(required = false) CancellationFault fault,
            @RequestParam(required = false) CancellationKind kind,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String driverId,
            @RequestParam(required = false) String riderId,
            @RequestParam(required = false) String routePostId,
            @RequestParam(required = false) String search) {
        return ApiResponse.ok(tripOpsService.cancellationCounts(
                new TripServiceClient.CancellationQuery(null, fault, kind, from, to, driverId, riderId, routePostId, search)));
    }
}
