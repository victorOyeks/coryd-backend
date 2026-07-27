package org.coryd.identity.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.enums.OtpChannel;
import org.coryd.common.enums.UserRole;
import org.coryd.common.exception.BadRequestException;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.common.kafka.KafkaTopics;
import org.coryd.common.kafka.events.OtpIssuedEvent;
import org.coryd.common.security.CoRydUserDetails;
import org.coryd.common.security.JwtUtil;
import org.coryd.identity.client.ProfileServiceClient;
import org.coryd.identity.dto.AddRoleRequest;
import org.coryd.identity.dto.AuthResponse;
import org.coryd.identity.dto.LoginRequest;
import org.coryd.identity.dto.OnboardingStatusResponse;
import org.coryd.identity.dto.RefreshTokenRequest;
import org.coryd.identity.dto.ResendOtpRequest;
import org.coryd.identity.dto.SetPasswordRequest;
import org.coryd.identity.dto.SignupRequest;
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
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final int OTP_TTL_MINUTES = 5;
    private static final int REFRESH_TOKEN_TTL_DAYS = 30;
    private static final String FIXED_DEV_OTP = "000333";
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
        if (userRepository.existsByPhone(request.phone())) {
            throw new BadRequestException("An account with this phone number already exists - please log in");
        }

        userRepository.save(User.builder()
                .phone(request.phone())
                .roles(new HashSet<>(Set.of(role)))
                .build());
        issueOtp(request.phone(), OtpChannel.SMS);
    }

    @Transactional
    public void resendOtp(ResendOtpRequest request) {
        issueOtp(request.phone(), request.channelOrDefault());
    }

    private void issueOtp(String phone, OtpChannel channel) {
        String code = isDevOrTest() ? FIXED_DEV_OTP : String.format("%06d", RANDOM.nextInt(1_000_000));
        otpCodeRepository.save(OtpCode.builder()
                .phone(phone)
                .code(code)
                .expiresAt(Instant.now().plus(OTP_TTL_MINUTES, ChronoUnit.MINUTES))
                .build());
        kafkaTemplate.send(KafkaTopics.OTP_ISSUED, OtpIssuedEvent.builder()
                .phone(phone)
                .code(code)
                .channel(channel)
                .build());
    }

    /** dev/test get a fixed OTP so testers never have to check an inbox. Never enabled otherwise. */
    private boolean isDevOrTest() {
        for (String profile : environment.getActiveProfiles()) {
            if ("dev".equalsIgnoreCase(profile) || "test".equalsIgnoreCase(profile)) {
                return true;
            }
        }
        return false;
    }

    @Transactional
    public AuthResponse verifyOtp(VerifyOtpRequest request) {
        OtpCode otp = otpCodeRepository.findTopByPhoneAndUsedFalseOrderByCreatedAtDesc(request.phone())
                .orElseThrow(() -> new BadRequestException("No pending OTP for this phone number"));

        if (otp.getExpiresAt().isBefore(Instant.now())) {
            throw new BadRequestException("OTP has expired");
        }
        if (!otp.getCode().equals(request.code())) {
            throw new BadRequestException("Incorrect OTP");
        }

        otp.setUsed(true);
        otpCodeRepository.save(otp);

        User user = userRepository.findByPhone(request.phone())
                .orElseThrow(() -> new ResourceNotFoundException("No account for this phone number"));

        if (user.getPhoneVerifiedAt() == null) {
            user.setPhoneVerifiedAt(Instant.now());
            userRepository.save(user);
        }

        // A user reaching signup-OTP verification always holds exactly the one
        // role they just signed up with - adding a second role later goes
        // through /auth/roles/add instead, never this path.
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

        User user = userRepository.findByPhone(request.phone())
                .orElseThrow(() -> new BadRequestException("Incorrect phone number or password"));

        if (user.getPasswordHash() == null) {
            throw new BadRequestException("Account setup is incomplete - finish onboarding before signing in");
        }
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BadRequestException("Incorrect phone number or password");
        }
        if (!user.getRoles().contains(activeRole)) {
            throw new BadRequestException("You don't have a " + activeRole + " account - please sign up first");
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

    public OnboardingStatusResponse onboardingStatus(CoRydUserDetails principal, String bearerToken) {
        User user = userRepository.findById(principal.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("Account not found"));

        boolean phoneVerified = user.getPhoneVerifiedAt() != null;
        boolean passwordSet = user.getPasswordHash() != null;
        boolean profileComplete = profileServiceClient.hasProfile(principal.getActiveRole(), bearerToken);
        boolean termsAccepted = user.getTermsAcceptedAt() != null;

        return new OnboardingStatusResponse(phoneVerified, passwordSet, profileComplete, termsAccepted);
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
        return new AuthResponse(user.getId(), user.getPhone(), activeRole.name(), roleNames,
                accessToken, expiresIn, refreshTokenValue, refreshExpiresIn);
    }
}
