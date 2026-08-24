package org.coryd.profile.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.coryd.common.security.CoRydUserDetails;
import org.coryd.profile.dto.VehicleRegistrationRequest;
import org.coryd.profile.dto.VehicleSummary;
import org.coryd.profile.dto.VerificationDecisionRequest;
import org.coryd.profile.entity.Vehicle;
import org.coryd.profile.service.VehicleService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/vehicles")
@RequiredArgsConstructor
public class VehicleController {

    private final VehicleService vehicleService;

    @PostMapping("/drivers/{driverId}")
    public ApiResponse<Vehicle> register(@AuthenticationPrincipal CoRydUserDetails principal,
                                          @PathVariable String driverId,
                                          @Valid @RequestBody VehicleRegistrationRequest request) {
        return ApiResponse.ok(vehicleService.register(driverId, principal.getUserId(), request));
    }

    @PutMapping("/{vehicleId}")
    public ApiResponse<Vehicle> update(@AuthenticationPrincipal CoRydUserDetails principal,
                                        @PathVariable String vehicleId,
                                        @Valid @RequestBody VehicleRegistrationRequest request) {
        return ApiResponse.ok(vehicleService.update(vehicleId, principal.getUserId(), request));
    }

    @PatchMapping("/{vehicleId}/verification")
    public ApiResponse<Vehicle> decide(@AuthenticationPrincipal CoRydUserDetails principal,
                                        @PathVariable String vehicleId,
                                        @Valid @RequestBody VerificationDecisionRequest request) {
        return ApiResponse.ok(vehicleService.decide(vehicleId, principal.getActiveRole(), request));
    }

    @GetMapping("/drivers/{driverId}")
    public ApiResponse<List<Vehicle>> byDriver(@PathVariable String driverId) {
        return ApiResponse.ok(vehicleService.findByDriver(driverId));
    }

    @GetMapping("/{vehicleId}")
    public ApiResponse<Vehicle> byId(@PathVariable String vehicleId) {
        return ApiResponse.ok(vehicleService.findById(vehicleId));
    }

    @GetMapping("/batch")
    public ApiResponse<List<VehicleSummary>> batch(@RequestParam List<String> ids) {
        return ApiResponse.ok(vehicleService.findSummariesByIds(ids));
    }
}
