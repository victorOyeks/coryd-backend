package org.coryd.identity.controller.internal;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.coryd.identity.dto.ops.ChangeOpsRoleRequest;
import org.coryd.identity.dto.ops.InviteOpsAdminRequest;
import org.coryd.identity.dto.ops.OpsAdminView;
import org.coryd.identity.dto.ops.OpsTeamActionRequest;
import org.coryd.identity.service.OpsTeamService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The ops console's admin team (ops-service only, which checks the caller's ops role; every change is checked again here
 * against the acting admin's current role). Gated by InternalApiKeyFilter via /internal/**.
 */
@Hidden
@RestController
@RequestMapping("/internal/ops-admins")
@RequiredArgsConstructor
public class InternalOpsAdminController {

    private final OpsTeamService opsTeamService;

    @GetMapping
    public ApiResponse<List<OpsAdminView>> list() {
        return ApiResponse.ok(opsTeamService.list());
    }

    @GetMapping("/{userId}")
    public ApiResponse<OpsAdminView> get(@PathVariable String userId) {
        return ApiResponse.ok(opsTeamService.get(userId));
    }

    /** The console loaded (GET /ops/me) - "last seen" moves on. */
    @PostMapping("/{userId}/seen")
    public ApiResponse<Void> seen(@PathVariable String userId) {
        opsTeamService.touch(userId);
        return ApiResponse.ok(null);
    }

    @PostMapping("/invites")
    public ApiResponse<OpsAdminView> invite(@Valid @RequestBody InviteOpsAdminRequest request) {
        return ApiResponse.ok("Invite sent", opsTeamService.invite(request));
    }

    @PostMapping("/{userId}/invite/resend")
    public ApiResponse<OpsAdminView> resend(@PathVariable String userId, @Valid @RequestBody OpsTeamActionRequest request) {
        return ApiResponse.ok("Invite sent again", opsTeamService.resendInvite(userId, request));
    }

    @PostMapping("/{userId}/invite/revoke")
    public ApiResponse<OpsAdminView> revoke(@PathVariable String userId, @Valid @RequestBody OpsTeamActionRequest request) {
        return ApiResponse.ok("Invite revoked", opsTeamService.revokeInvite(userId, request));
    }

    @PostMapping("/{userId}/role")
    public ApiResponse<OpsAdminView> role(@PathVariable String userId, @Valid @RequestBody ChangeOpsRoleRequest request) {
        return ApiResponse.ok("Ops role changed", opsTeamService.changeRole(userId, request));
    }

    @PostMapping("/{userId}/deactivate")
    public ApiResponse<OpsAdminView> deactivate(@PathVariable String userId, @Valid @RequestBody OpsTeamActionRequest request) {
        return ApiResponse.ok("Admin deactivated", opsTeamService.deactivate(userId, request));
    }

    @PostMapping("/{userId}/reactivate")
    public ApiResponse<OpsAdminView> reactivate(@PathVariable String userId, @Valid @RequestBody OpsTeamActionRequest request) {
        return ApiResponse.ok("Admin reactivated", opsTeamService.reactivate(userId, request));
    }
}
