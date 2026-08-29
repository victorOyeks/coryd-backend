package org.coryd.profile.controller;

import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.coryd.profile.dto.DriverSummary;
import org.coryd.profile.entity.DriverProfile;
import org.coryd.profile.service.DriverProfileService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Service-to-service only (trip-service's ProfileServiceClient) - never called by a mobile client directly, gated by InternalApiKeyFilter via the /internal/** prefix and hidden from Swagger. */
@RestController
@RequestMapping("/internal/drivers")
@RequiredArgsConstructor
public class InternalDriverController {

    private final DriverProfileService driverProfileService;

    @GetMapping("/{userId}")
    public ApiResponse<DriverProfile> get(@PathVariable String userId) {
        return ApiResponse.ok(driverProfileService.get(userId));
    }

    @GetMapping("/batch")
    public ApiResponse<List<DriverSummary>> batch(@RequestParam List<String> userIds) {
        return ApiResponse.ok(driverProfileService.getBatch(userIds));
    }
}
