package org.coryd.identity.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.coryd.common.security.CoRydUserDetails;
import org.coryd.identity.dto.AuthResponse;
import org.coryd.identity.dto.LoginRequest;
import org.coryd.identity.dto.RefreshTokenRequest;
import org.coryd.identity.dto.ResendOtpRequest;
import org.coryd.identity.dto.SetPasswordRequest;
import org.coryd.identity.dto.SignupRequest;
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
    public ApiResponse<Void> signup(@Valid @RequestBody SignupRequest request) {
        authService.signup(request);
        return ApiResponse.ok("OTP sent", null);
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
}
