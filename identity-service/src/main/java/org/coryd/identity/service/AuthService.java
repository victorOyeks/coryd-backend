package org.coryd.identity.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.enums.OtpChannel;
import org.coryd.common.enums.OtpPurpose;
import org.coryd.common.enums.UserRole;
import org.coryd.common.exception.BadRequestException;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.common.exception.RoleNotHeldException;
import org.coryd.common.kafka.KafkaTopics;
import org.coryd.common.kafka.events.OtpIssuedEvent;
import org.coryd.common.security.CoRydUserDetails;
import org.coryd.common.security.JwtUtil;
import org.coryd.common.util.Names;
import org.coryd.common.util.PhoneNumbers;
import org.coryd.identity.client.ProfileServiceClient;
import org.coryd.identity.dto.AddRoleRequest;
import org.coryd.identity.dto.AuthResponse;
import org.coryd.identity.dto.ChangePasswordRequest;
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
import org.coryd.identity.entity.OtpCode;
import org.coryd.identity.entity.RefreshToken;
import org.coryd.identity.entity.User;
import org.coryd.identity.repository.OtpCodeRepository;
import org.coryd.identity.repository.RefreshTokenRepository;
import org.coryd.identity.repository.UserRepository;
import org.springframework.core.env.Environment;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final int OTP_TTL_MINUTES = 5;
    private static final int REFRESH_TOKEN_TTL_DAYS = 30;
    private static final String DEV_BYPASS_OTP = "000333";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final OtpCodeRepository otpCodeRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtUtil jwtUtil;
    private final PasswordEncoder passwordEncoder;
    private final Environment environment;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ProfileServiceClient profileServiceClient;

    @Transactional
    public void signup(SignupRequest request) {
        UserRole role = request.role() != null ? request.role() : UserRole.RIDER;
        if (role == UserRole.OPS_ADMIN) {
            throw new BadRequestException("OPS_ADMIN cannot be self-assigned");
        }

        String phone = PhoneNumbers.normalize(request.phone());
        Optional<User> existing = userRepository.findByPhone(phone);
        boolean fullyOnboarded = existing.isPresent()
                && existing.get().getPhoneVerifiedAt() != null
                && existing.get().getPasswordHash() != null;
        if (fullyOnboarded) {
            throw new BadRequestException("An account with this phone number already exists - please log in");
        }

        // Either a new phone, or a signup abandoned before completing onboarding - never verified,
        // or verified but no password ever set (verifyOtp() already hands out a token, so a stuck
        // user could have added a second role via /auth/roles/add before abandoning; only a
        // never-verified row is safe to reset roles on, an already-verified one just gains this one).
        User user = existing.orElseGet(() -> User.builder().phone(phone).build());
        if (user.getPhoneVerifiedAt() == null) {
            user.setRoles(new HashSet<>(Set.of(role)));
        } else {
            user.getRoles().add(role);
        }
        userRepository.save(user);

        issueOtp(phone, OtpChannel.SMS, OtpPurpose.SIGNUP);
    }

    @Transactional
    public void resendOtp(ResendOtpRequest request) {
        issueOtp(PhoneNumbers.normalize(request.phone()), request.channelOrDefault(), OtpPurpose.SIGNUP);
    }

    private void issueOtp(String phone, OtpChannel channel, OtpPurpose purpose) {
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        otpCodeRepository.save(OtpCode.builder()
                .phone(phone)
                .code(code)
                .purpose(purpose)
                .expiresAt(Instant.now().plus(OTP_TTL_MINUTES, ChronoUnit.MINUTES))
                .build());
        kafkaTemplate.send(KafkaTopics.OTP_ISSUED, OtpIssuedEvent.builder()
                .phone(phone)
                .code(code)
                .channel(channel)
                .purpose(purpose)
                .build());
    }

    private boolean isDevOrTest() {
        for (String profile : environment.getActiveProfiles()) {
            if ("dev".equalsIgnoreCase(profile) || "test".equalsIgnoreCase(profile)) {
                return true;
            }
        }
        return false;
    }

    /** The real, randomly-generated code always works. In dev/test only, 000333 also works - lets
     * testers without access to the phone complete the flow, without disabling real OTP delivery. */
    private boolean codeMatches(OtpCode otp, String enteredCode) {
        return otp.getCode().equals(enteredCode) || (isDevOrTest() && DEV_BYPASS_OTP.equals(enteredCode));
    }

    @Transactional
    public AuthResponse verifyOtp(VerifyOtpRequest request) {
        OtpCode otp = otpCodeRepository.findTopByPhoneAndPurposeAndUsedFalseOrderByCreatedAtDesc(PhoneNumbers.normalize(request.phone()), OtpPurpose.SIGNUP)
                .orElseThrow(() -> new BadRequestException("No pending OTP for this phone number"));

        if (otp.getExpiresAt().isBefore(Instant.now())) {
            throw new BadRequestException("OTP has expired");
        }
        if (!codeMatches(otp, request.code())) {
            throw new BadRequestException("Incorrect OTP");
        }

        otp.setUsed(true);
        otpCodeRepository.save(otp);

        User user = userRepository.findByPhone(PhoneNumbers.normalize(request.phone()))
                .orElseThrow(() -> new ResourceNotFoundException("No account for this phone number"));

        if (user.getPhoneVerifiedAt() == null) {
            user.setPhoneVerifiedAt(Instant.now());
            userRepository.save(user);
        }

        UserRole role = user.getRoles().iterator().next();

        return issueTokens(user, role);
    }

    @Transactional
    public void setPassword(String userId, SetPasswordRequest request) {
        if (!request.password().equals(request.confirmPassword())) {
            throw new BadRequestException("Password and confirmation do not match");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found"));

        user.setPasswordHash(passwordEncoder.encode(request.password()));
        userRepository.save(user);
    }

    /** Account > Update your password - unlike setPassword (onboarding, no existing password to check against), this verifies the current one first. */
    @Transactional
    public void changePassword(String userId, ChangePasswordRequest request) {
        if (!request.newPassword().equals(request.confirmPassword())) {
            throw new BadRequestException("Password and confirmation do not match");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found"));
        
        if (user.getPasswordHash() == null) {
            throw new BadRequestException("No password set yet - use set-password instead");
        }
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new BadRequestException("Current password is incorrect");
        }

        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);
    }

    /** Logged-out counterpart to changePassword - no current password to check, an OTP proves phone ownership instead. Silent no-op for an unregistered phone so the response never reveals whether an account exists. */
    @Transactional
    public void forgotPassword(ForgotPasswordRequest request) {
        String phone = PhoneNumbers.normalize(request.phone());
        if (userRepository.existsByPhone(phone)) {
            issueOtp(phone, OtpChannel.SMS, OtpPurpose.PASSWORD_RESET);
        }
    }

    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        if (!request.newPassword().equals(request.confirmPassword())) {
            throw new BadRequestException("Password and confirmation do not match");
        }

        OtpCode otp = otpCodeRepository.findTopByPhoneAndPurposeAndUsedFalseOrderByCreatedAtDesc(
                        PhoneNumbers.normalize(request.phone()), OtpPurpose.PASSWORD_RESET)
                .orElseThrow(() -> new BadRequestException("No pending password reset for this phone number"));

        if (otp.getExpiresAt().isBefore(Instant.now())) {
            throw new BadRequestException("OTP has expired");
        }
        if (!codeMatches(otp, request.code())) {
            throw new BadRequestException("Incorrect OTP");
        }
        otp.setUsed(true);
        otpCodeRepository.save(otp);

        User user = userRepository.findByPhone(PhoneNumbers.normalize(request.phone()))
                .orElseThrow(() -> new ResourceNotFoundException("Account not found"));

        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);

        // Whoever forgot the password may have done so because the account was
        // compromised - close every session that predates the reset.
        refreshTokenRepository.revokeAllForUser(user.getId());
    }

    @Transactional
    public void acceptTerms(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found"));

        user.setTermsAcceptedAt(Instant.now());
        userRepository.save(user);
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        UserRole activeRole = request.roleOrDefault();

        User user = userRepository.findByPhone(PhoneNumbers.normalize(request.phone()))
                .orElseThrow(() -> new BadRequestException("Incorrect phone number or password"));

        if (user.getPasswordHash() == null) {
            throw new BadRequestException("Account setup is incomplete - finish onboarding before signing in");
        }
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BadRequestException("Incorrect phone number or password");
        }
        if (!user.getRoles().contains(activeRole)) {
            throw new RoleNotHeldException("You don't have a " + activeRole + " account - please sign up first", user.getRoles());
        }

        return issueTokens(user, activeRole);
    }

    /** In-app "switch to Driver mode" (or vice versa) for the first time: attach the role to the already-authenticated account, no OTP needed. */
    @Transactional
    public AuthResponse addRole(String userId, AddRoleRequest request) {
        UserRole role = request.role();
        if (role == UserRole.OPS_ADMIN) {
            throw new BadRequestException("OPS_ADMIN cannot be self-assigned");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found"));
        if (user.getRoles().contains(role)) {
            throw new BadRequestException("You already have a " + role + " account");
        }

        user.getRoles().add(role);
        userRepository.save(user);

        return issueTokens(user, role);
    }

    /** In-app mode toggle for a role already held - re-scopes the session without granting anything new, so no OPS_ADMIN guard is needed here. */
    @Transactional
    public AuthResponse switchRole(String userId, AddRoleRequest request) {
        UserRole role = request.role();

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found"));
        if (!user.getRoles().contains(role)) {
            throw new BadRequestException("You don't have a " + role + " account - add it first");
        }

        return issueTokens(user, role);
    }

    public OnboardingStatusResponse onboardingStatus(CoRydUserDetails principal, String bearerToken) {
        User user = userRepository.findById(principal.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("Account not found"));

        boolean phoneVerified = user.getPhoneVerifiedAt() != null;
        boolean passwordSet = user.getPasswordHash() != null;
        boolean profileComplete = profileServiceClient.hasProfile(principal.getActiveRole(), bearerToken);
        boolean termsAccepted = user.getTermsAcceptedAt() != null;

        return new OnboardingStatusResponse(phoneVerified, passwordSet, profileComplete, termsAccepted);
    }

    /** The one place a logged-in user's own phone number is available - profile-service's DriverProfile/RiderProfile never store it, and the only other place it lives (GET /internal/users/{userId}/contact) is service-to-service only, not reachable with a user's own JWT. */
    public UserContactResponse getMyContact(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found"));
        return new UserContactResponse(user.getId(), user.getFullName(), user.getPhone());
    }

    @Transactional
    public AuthResponse refresh(RefreshTokenRequest request) {
        RefreshToken existing = refreshTokenRepository.findByTokenAndRevokedFalse(request.refreshToken())
                .orElseThrow(() -> new BadRequestException("Invalid or expired refresh token"));

        if (existing.getExpiresAt().isBefore(Instant.now())) {
            throw new BadRequestException("Refresh token has expired");
        }

        existing.setRevoked(true);
        refreshTokenRepository.save(existing);

        User user = userRepository.findById(existing.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("Account no longer exists"));

        return issueTokens(user, existing.getActiveRole());
    }

    private AuthResponse issueTokens(User user, UserRole activeRole) {
        String accessToken = jwtUtil.generateToken(user.getId(), user.getPhone(), user.getFullName(), activeRole);

        String refreshTokenValue = UUID.randomUUID().toString();
        refreshTokenRepository.save(RefreshToken.builder()
                .userId(user.getId())
                .token(refreshTokenValue)
                .activeRole(activeRole)
                .expiresAt(Instant.now().plus(REFRESH_TOKEN_TTL_DAYS, ChronoUnit.DAYS))
                .build());

        Set<String> roleNames = user.getRoles().stream().map(Enum::name).collect(Collectors.toSet());
        long expiresIn = jwtUtil.getExpirationMs() / 1000;
        long refreshExpiresIn = Duration.ofDays(REFRESH_TOKEN_TTL_DAYS).getSeconds();
        // fullName carries only the first name - the key stays for the clients already bound to
        // it, the JWT claim keeps the full name for service-to-service use.
        return new AuthResponse(user.getId(), Names.firstName(user.getFullName()), user.getPhone(), activeRole.name(), roleNames,
                accessToken, expiresIn, refreshTokenValue, refreshExpiresIn);
    }
}
