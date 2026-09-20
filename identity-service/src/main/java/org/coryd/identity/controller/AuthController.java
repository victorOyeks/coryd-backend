package org.coryd.identity.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.coryd.common.security.CoRydUserDetails;
import org.coryd.identity.dto.AddRoleRequest;
import org.coryd.identity.dto.AuthResponse;
import org.coryd.identity.dto.BiometricPreferenceRequest;
import org.coryd.identity.dto.ChangePasswordRequest;
import org.coryd.identity.dto.DeviceResponse;
import org.coryd.identity.dto.ForgotPasswordRequest;
import org.coryd.identity.dto.LoginRequest;
import org.coryd.identity.dto.OnboardingStatusResponse;
import org.coryd.identity.dto.RefreshTokenRequest;
import org.coryd.identity.dto.ResendOtpRequest;
import org.coryd.identity.dto.ResetPasswordRequest;
import org.coryd.identity.dto.SetPasswordRequest;
import org.coryd.identity.dto.SignupRequest;
import org.coryd.identity.dto.UserContactResponse;
import org.coryd.identity.dto.VerifyOtpRequest;
import org.coryd.identity.service.AuthService;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    /** The app sends its device id on every authenticated call; these endpoints are the ones that read it. */
    private static final String DEVICE_ID_HEADER = "X-Device-Id";

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

    /** Account > Update your password - for an already-onboarded user changing their password, unlike set-password's onboarding flow. */
    @PostMapping("/change-password")
    public ApiResponse<Void> changePassword(@AuthenticationPrincipal CoRydUserDetails principal,
                                             @Valid @RequestBody ChangePasswordRequest request) {
        authService.changePassword(principal.getUserId(), request);
        return ApiResponse.ok("Password changed", null);
    }

    /** Logged-out - the user can't authenticate to prove who they are, so an OTP does that instead. Always returns the same message, whether or not the phone is registered. */
    @PostMapping("/forgot-password")
    public ApiResponse<Void> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        authService.forgotPassword(request);
        return ApiResponse.ok("If this number is registered, an OTP has been sent", null);
    }

    @PostMapping("/reset-password")
    public ApiResponse<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request);
        return ApiResponse.ok("Password reset", null);
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

    /** In-app "switch to Driver mode" (or vice versa) for the first time - the caller is already logged in, so no OTP is needed. */
    @PostMapping("/roles/add")
    public ApiResponse<AuthResponse> addRole(@AuthenticationPrincipal CoRydUserDetails principal,
                                              @Valid @RequestBody AddRoleRequest request) {
        return ApiResponse.ok(authService.addRole(principal.getUserId(), request));
    }

    /** In-app mode toggle for a role the caller already holds - unlike roles/add, this never grants a new capability, just re-scopes the session. */
    @PostMapping("/roles/switch")
    public ApiResponse<AuthResponse> switchRole(@AuthenticationPrincipal CoRydUserDetails principal,
                                                 @Valid @RequestBody AddRoleRequest request) {
        return ApiResponse.ok(authService.switchRole(principal.getUserId(), request));
    }

    /** Lets the app resume onboarding at the right screen after an interruption, using whatever token it last received. */
    @GetMapping("/onboarding-status")
    public ApiResponse<OnboardingStatusResponse> onboardingStatus(@AuthenticationPrincipal CoRydUserDetails principal,
                                                                    HttpServletRequest request) {
        return ApiResponse.ok(authService.onboardingStatus(principal, request.getHeader(HttpHeaders.AUTHORIZATION)));
    }

    /** The only self-serve way to get a logged-in user's own phone number - profile-service's driver/rider profiles never store it. */
    @GetMapping("/me")
    public ApiResponse<UserContactResponse> me(@AuthenticationPrincipal CoRydUserDetails principal) {
        return ApiResponse.ok(authService.getMyContact(principal.getUserId()));
    }

    /**
     * Where the user is signed in. The device making the request is flagged {@code current} so the
     * app can label it and warn before it signs itself out.
     *
     * <p>Sent as a header rather than a body because this is a GET, and the app already has the id
     * to hand on every call.
     */
    @GetMapping("/devices")
    public ApiResponse<List<DeviceResponse>> devices(@AuthenticationPrincipal CoRydUserDetails principal,
                                                      @RequestHeader(value = DEVICE_ID_HEADER, required = false) String deviceId) {
        return ApiResponse.ok(authService.listDevices(principal.getUserId(), deviceId));
    }

    /** Signs one device out and kills every refresh token bound to it - the answer to a lost or stolen phone. */
    @DeleteMapping("/devices/{deviceId}")
    public ApiResponse<Void> revokeDevice(@AuthenticationPrincipal CoRydUserDetails principal,
                                           @PathVariable String deviceId) {
        authService.revokeDevice(principal.getUserId(), deviceId);
        return ApiResponse.ok("Device signed out", null);
    }

    /**
     * Records that the user enabled or disabled fingerprint sign-in on a device.
     *
     * <p>Bookkeeping for the devices screen only. No fingerprint reaches this service - the
     * platform does not expose one - so this call authenticates nothing and unlocks nothing.
     */
    @PostMapping("/devices/biometric")
    public ApiResponse<Void> setBiometricPreference(@AuthenticationPrincipal CoRydUserDetails principal,
                                                     @Valid @RequestBody BiometricPreferenceRequest request) {
        authService.setBiometricPreference(principal.getUserId(), request);
        return ApiResponse.ok("Preference saved", null);
    }
}
