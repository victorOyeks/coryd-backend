package org.coryd.identity.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.coryd.identity.dto.AuthResponse;
import org.coryd.identity.dto.ops.AcceptOpsInviteRequest;
import org.coryd.identity.dto.ops.OpsInvitePreview;
import org.coryd.identity.service.OpsTeamService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The console's "Set up your account" page, from the invite email - no session yet, the one-time token is the proof. */
@RestController
@RequestMapping("/auth/ops-invite")
@RequiredArgsConstructor
public class OpsInviteController {

    private final OpsTeamService opsTeamService;

    /** Who the invite is for. 400 with the reason when the link is expired, used or replaced. */
    @GetMapping
    public ApiResponse<OpsInvitePreview> preview(@RequestParam String token) {
        return ApiResponse.ok(opsTeamService.preview(token));
    }

    /** Sets the password (once) and returns a console session, like /auth/login with role OPS_ADMIN. */
    @PostMapping("/accept")
    public ApiResponse<AuthResponse> accept(@Valid @RequestBody AcceptOpsInviteRequest request) {
        return ApiResponse.ok("Welcome to the CoRyd ops console", opsTeamService.accept(request));
    }
}
