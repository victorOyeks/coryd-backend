package org.coryd.profile.controller.internal;

import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.coryd.profile.dto.VehicleSummary;
import org.coryd.profile.entity.Vehicle;
import org.coryd.profile.service.VehicleService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Service-to-service only (trip-service's ProfileServiceClient) - never called by a mobile client directly, gated by InternalApiKeyFilter via the /internal/** prefix and hidden from Swagger.
 * Previously GET /vehicles/{vehicleId} shared a path with the public PUT /vehicles/{vehicleId} update endpoint - since InternalApiKeyFilter matches on path only, not method, that meant a driver's own vehicle update was silently gated behind the internal key too. Splitting this onto its own path fixes that as a side effect. */
@RestController
@RequestMapping("/internal/vehicles")
@RequiredArgsConstructor
public class InternalVehicleController {

    private final VehicleService vehicleService;

    @GetMapping("/{vehicleId}")
    public ApiResponse<Vehicle> byId(@PathVariable String vehicleId) {
        return ApiResponse.ok(vehicleService.findById(vehicleId));
    }

    @GetMapping("/batch")
    public ApiResponse<List<VehicleSummary>> batch(@RequestParam List<String> ids) {
        return ApiResponse.ok(vehicleService.findSummariesByIds(ids));
    }
}
