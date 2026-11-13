package org.coryd.ops.controller;

import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.coryd.common.response.PagedResponse;
import org.coryd.ops.dto.DriverDirectoryCounts;
import org.coryd.ops.dto.DriverDirectoryItem;
import org.coryd.common.enums.DriverDirectorySort;
import org.coryd.common.enums.DriverDirectoryStatus;
import org.coryd.ops.dto.DriverProfileView;
import org.coryd.ops.service.DriverDirectoryService;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Drivers Directory - every driver with their car, trips, rating, takings, reliability and status; and one driver's profile. */
@RestController
@RequestMapping("/drivers")
@RequiredArgsConstructor
public class DriverDirectoryController {

    private final DriverDirectoryService driverDirectoryService;
    private final org.coryd.ops.service.AccountOpsService accountOpsService;

    /** status is omitted for the "All" tab. Newest sign-up first unless sorted. */
    @GetMapping
    public ApiResponse<PagedResponse<DriverDirectoryItem>> drivers(@RequestParam(required = false) DriverDirectoryStatus status,
                                                                   @RequestParam(required = false) String search,
                                                                   @RequestParam(required = false) DriverDirectorySort sort,
                                                                   @RequestParam(required = false) Sort.Direction direction,
                                                                   @RequestParam(defaultValue = "0") int page,
                                                                   @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(driverDirectoryService.drivers(status, search, sort, direction, page, size));
    }

    /** The tab badges - they follow the same search as the list. */
    @GetMapping("/counts")
    public ApiResponse<DriverDirectoryCounts> counts(@RequestParam(required = false) String search) {
        return ApiResponse.ok(driverDirectoryService.counts(search));
    }

    /**
     * Change a driver's vehicle - send only what changes, with a reason. Changing the plate, make, model or year of an
     * approved vehicle sends its registration back to review (sentBackToReview).
     */
    @org.springframework.web.bind.annotation.PatchMapping("/{driverId}/vehicles/{vehicleId}")
    public ApiResponse<org.coryd.ops.dto.AccountEditResult> editVehicle(
            @org.springframework.security.core.annotation.AuthenticationPrincipal org.coryd.common.security.CoRydUserDetails admin,
            @PathVariable String driverId, @PathVariable String vehicleId,
            @jakarta.validation.Valid @org.springframework.web.bind.annotation.RequestBody org.coryd.ops.dto.EditVehicleRequest request) {
        return ApiResponse.ok("Vehicle updated", accountOpsService.editVehicle(driverId, vehicleId, request, admin));
    }

    /** Give a driver locked out of the licence check their tries back. note is optional. Returns their tries now. */
    @org.springframework.web.bind.annotation.PostMapping("/{driverId}/verification/licence/reset-attempts")
    public ApiResponse<com.fasterxml.jackson.databind.JsonNode> resetLicenceAttempts(
            @org.springframework.security.core.annotation.AuthenticationPrincipal org.coryd.common.security.CoRydUserDetails admin,
            @PathVariable String driverId,
            @jakarta.validation.Valid @org.springframework.web.bind.annotation.RequestBody(required = false) org.coryd.ops.dto.OpsNoteRequest request) {
        return ApiResponse.ok("Tries reset", accountOpsService.resetLicenceAttempts(driverId, request == null ? null : request.note(), admin));
    }

    /** The profile drawer: driver, car, performance, account and any suspension. */
    @GetMapping("/{driverId}")
    public ApiResponse<DriverProfileView> driver(@PathVariable String driverId) {
        return ApiResponse.ok(driverDirectoryService.driver(driverId));
    }
}
