package org.coryd.identity.controller.internal;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.coryd.identity.dto.mfa.MfaCodeRequest;
import org.coryd.identity.dto.mfa.MfaSetupView;
import org.coryd.identity.dto.mfa.MfaStatusView;
import org.coryd.identity.dto.mfa.RecoveryCodesView;
import org.coryd.identity.dto.ops.OpsAdminView;
import org.coryd.identity.dto.ops.OpsTeamActionRequest;
import org.coryd.identity.service.OpsMfaService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * An ops admin's two-factor sign-in, for ops-service: /ops/me/mfa/** (the userId is the signed-in admin's, from their
 * token) and the super admin's reset (/ops/team/{id}/mfa/reset, checked again here). Gated by InternalApiKeyFilter.
 */
@Hidden
@RestController
@RequestMapping("/internal/ops-admins/{userId}/mfa")
@RequiredArgsConstructor
public class InternalOpsMfaController {

    private final OpsMfaService opsMfaService;

    @GetMapping
    public ApiResponse<MfaStatusView> status(@PathVariable String userId) {
        return ApiResponse.ok(opsMfaService.status(userId));
    }

    @PostMapping("/setup")
    public ApiResponse<MfaSetupView> setup(@PathVariable String userId) {
        return ApiResponse.ok(opsMfaService.setup(userId));
    }

    @PostMapping("/enable")
    public ApiResponse<RecoveryCodesView> enable(@PathVariable String userId, @Valid @RequestBody MfaCodeRequest request) {
        return ApiResponse.ok("Two-factor sign-in is on", opsMfaService.enable(userId, request));
    }

    @PostMapping("/disable")
    public ApiResponse<MfaStatusView> disable(@PathVariable String userId, @Valid @RequestBody MfaCodeRequest request) {
        return ApiResponse.ok("Two-factor sign-in is off", opsMfaService.disable(userId, request));
    }

    @PostMapping("/recovery-codes")
    public ApiResponse<RecoveryCodesView> recoveryCodes(@PathVariable String userId, @Valid @RequestBody MfaCodeRequest request) {
        return ApiResponse.ok("New recovery codes - the old ones no longer work", opsMfaService.regenerateRecoveryCodes(userId, request));
    }

    @PostMapping("/reset")
    public ApiResponse<OpsAdminView> reset(@PathVariable String userId, @Valid @RequestBody OpsTeamActionRequest request) {
        return ApiResponse.ok("Two-factor sign-in reset - every session they had has ended", opsMfaService.reset(userId, request));
    }
}
