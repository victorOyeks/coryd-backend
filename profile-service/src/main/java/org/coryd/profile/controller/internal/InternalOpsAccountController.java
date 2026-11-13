package org.coryd.profile.controller.internal;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.coryd.profile.dto.OpsAccountProfile;
import org.coryd.profile.dto.OpsDetailsEditRequest;
import org.coryd.profile.dto.OpsEditResult;
import org.coryd.profile.dto.OpsVehicleEditRequest;
import org.coryd.profile.service.OpsAccountService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The ops console's account screens (ops-service only, which checks the caller is OPS_ADMIN and records each edit in the
 * account history). Gated by the internal key via the /internal/** prefix and hidden from Swagger.
 */
@Hidden
@RestController
@RequiredArgsConstructor
public class InternalOpsAccountController {

    private final OpsAccountService opsAccountService;

    @GetMapping("/internal/users/{userId}/ops-profile")
    public ApiResponse<OpsAccountProfile> profile(@PathVariable String userId) {
        return ApiResponse.ok(opsAccountService.profile(userId));
    }

    @PatchMapping("/internal/users/{userId}/details")
    public ApiResponse<OpsEditResult> editDetails(@PathVariable String userId, @Valid @RequestBody OpsDetailsEditRequest request) {
        return ApiResponse.ok("Details updated", opsAccountService.editDetails(userId, request));
    }

    @PatchMapping("/internal/drivers/{driverId}/vehicles/{vehicleId}")
    public ApiResponse<OpsEditResult> editVehicle(@PathVariable String driverId, @PathVariable String vehicleId,
                                                  @Valid @RequestBody OpsVehicleEditRequest request,
                                                  @RequestParam(required = false) String opsUserId,
                                                  @RequestParam(required = false) String opsUserName) {
        return ApiResponse.ok("Vehicle updated", opsAccountService.editVehicle(driverId, vehicleId, request, opsUserName, opsUserId));
    }
}
