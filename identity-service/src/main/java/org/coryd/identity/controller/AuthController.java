package org.coryd.identity.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.coryd.common.security.CoRydUserDetails;
import org.coryd.identity.dto.AddRoleRequest;
import org.coryd.identity.dto.AuthResponse;
import org.coryd.identity.dto.LoginRequest;
import org.coryd.identity.dto.RefreshTokenRequest;
import org.coryd.identity.dto.ResendOtpRequest;
import org.coryd.identity.dto.RoleChallengeRequest;
import org.coryd.identity.dto.RoleConfirmRequest;
import org.coryd.identity.dto.SetPasswordRequest;
import org.coryd.identity.dto.SignupRequest;
import org.coryd.identity.dto.SignupResponse;
import org.coryd.identity.dto.VerifyOtpRequest;
import org.coryd.identity.service.AuthService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/signup")
    public ApiResponse<SignupResponse> signup(@Valid @RequestBody SignupRequest request) {
        return ApiResponse.ok(authService.signup(request));
    }

    @PostMapping("/resend-otp")
    public ApiResponse<Void> resendOtp(@Valid @RequestBody ResendOtpRequest request) {
        authService.resendOtp(request);
        return ApiResponse.ok("OTP resent", null);
    }

    @PostMapping("/verify-otp")
    public ApiResponse<AuthResponse> verifyOtp(@Valid @RequestBody VerifyOtpRequest request) {
        return ApiResponse.ok(authService.verifyOtp(request));
    }

    @PostMapping("/set-password")
    public ApiResponse<Void> setPassword(@AuthenticationPrincipal CoRydUserDetails principal,
                                         @Valid @RequestBody SetPasswordRequest request) {
        authService.setPassword(principal.getUserId(), request);
        return ApiResponse.ok("Password set", null);
    }

    @PostMapping("/accept-terms")
    public ApiResponse<Void> acceptTerms(@AuthenticationPrincipal CoRydUserDetails principal) {
        authService.acceptTerms(principal.getUserId());
        return ApiResponse.ok("Terms accepted", null);
    }

    @PostMapping("/login")
    public ApiResponse<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok(authService.login(request));
    }

    @PostMapping("/refresh-token")
    public ApiResponse<AuthResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return ApiResponse.ok(authService.refresh(request));
    }

    /** Already logged in - e.g. a Rider opening the Driver app for the first time on the same account. */
    @PostMapping("/roles/add")
    public ApiResponse<AuthResponse> addRole(@AuthenticationPrincipal CoRydUserDetails principal,
                                              @Valid @RequestBody AddRoleRequest request) {
        return ApiResponse.ok(authService.addRole(principal.getUserId(), request));
    }

    /** Not logged in - phone already has an account under a different role; prove ownership with the password first. */
    @PostMapping("/roles/challenge")
    public ApiResponse<Void> challengeRoleAddition(@Valid @RequestBody RoleChallengeRequest request) {
        authService.initiateRoleAddition(request);
        return ApiResponse.ok("OTP sent", null);
    }

    @PostMapping("/roles/confirm")
    public ApiResponse<AuthResponse> confirmRoleAddition(@Valid @RequestBody RoleConfirmRequest request) {
        return ApiResponse.ok(authService.confirmRoleAddition(request));
    }
}
