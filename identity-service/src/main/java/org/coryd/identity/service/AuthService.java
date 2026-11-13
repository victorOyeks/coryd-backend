package org.coryd.identity.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.enums.HeardAboutUs;
import org.coryd.common.enums.OtpChannel;
import org.coryd.common.enums.OpsRole;
import org.coryd.common.enums.OtpPurpose;
import org.coryd.common.enums.UserRole;
import org.coryd.common.exception.BadRequestException;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.common.exception.UnauthorizedException;
import org.coryd.common.util.Names;
import org.coryd.common.exception.RoleNotHeldException;
import org.coryd.common.kafka.KafkaTopics;
import org.coryd.common.kafka.events.OtpIssuedEvent;
import org.coryd.common.security.CoRydUserDetails;
import org.coryd.common.security.JwtUtil;
import org.coryd.common.util.PhoneNumbers;
import org.coryd.identity.client.ProfileServiceClient;
import org.coryd.identity.dto.AddRoleRequest;
import org.coryd.identity.dto.AuthResponse;
import org.coryd.identity.dto.BiometricPreferenceRequest;
import org.coryd.identity.dto.ChangePasswordRequest;
import org.coryd.identity.dto.DeviceInfo;
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
import org.coryd.identity.entity.OtpCode;
import org.coryd.identity.entity.RefreshToken;
import org.coryd.identity.entity.User;
import org.coryd.identity.entity.UserDevice;
import org.coryd.identity.repository.OtpCodeRepository;
import org.coryd.identity.repository.RefreshTokenRepository;
import org.coryd.identity.repository.UserDeviceRepository;
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
import java.util.List;
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
    /**
     * How long after a rotation the spent token is still accepted as a retry rather than treated
     * as a replay. Long enough to cover a request whose response was lost and is retried on the
     * next screen tap; short enough that a token lifted from a device is almost certainly outside
     * it by the time anyone uses it.
     */
    private static final Duration REFRESH_REPLAY_GRACE = Duration.ofSeconds(60);

    private final UserRepository userRepository;
    private final BetaSignupService betaSignupService;
    private final OtpCodeRepository otpCodeRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final UserDeviceRepository userDeviceRepository;
    private final TokenFamilyRevoker tokenFamilyRevoker;
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
        if (user.getHeardAboutUs() == null && request.heardAboutUs() != null) {
            user.setHeardAboutUs(request.heardAboutUs());
            String other = request.heardAboutUsOther() == null ? "" : request.heardAboutUsOther().trim();
            user.setHeardAboutUsOther(request.heardAboutUs() == HeardAboutUs.OTHER && !other.isEmpty() ? other : null);
        }
        userRepository.save(user);
        betaSignupService.markJoined(phone);

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

        return issueTokens(user, role, request.device());
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

        // Ops admins sign in to the console with their email, sent in the same "phone" field.
        boolean byEmail = request.phone().contains("@");
        String wrong = byEmail ? "Incorrect email or password" : "Incorrect phone number or password";
        User user = (byEmail ? findByEmail(request.phone()) : userRepository.findByPhone(PhoneNumbers.normalize(request.phone())))
                .orElseThrow(() -> new BadRequestException(wrong));

        if (user.getPasswordHash() == null) {
            throw new BadRequestException(byEmail
                    ? "Finish setting up your account from the invite email before signing in"
                    : "Account setup is incomplete - finish onboarding before signing in");
        }
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BadRequestException(wrong);
        }
        if (!user.getRoles().contains(activeRole)) {
            throw new RoleNotHeldException("You don't have a " + activeRole + " account - please sign up first", user.getRoles());
        }

        return issueTokens(user, activeRole, request.device());
    }

    /** By email, ignoring case - and an admin created by hand with the email in the phone column (before emails existed). */
    private Optional<User> findByEmail(String email) {
        String normalized = email.trim().toLowerCase(java.util.Locale.ROOT);
        return userRepository.findByEmail(normalized).or(() -> userRepository.findByPhone(email.trim()));
    }

    /** A console session for an ops admin who just set their password from an invite (OpsTeamService). */
    @Transactional
    public AuthResponse opsSession(User user) {
        return issueTokens(user, UserRole.OPS_ADMIN, null, null);
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

        // Same handset, new role - keep the binding rather than handing back an unbound session.
        return issueTokens(user, role, registerDeviceId(user.getId(), request.deviceId()), null);
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

        return issueTokens(user, role, registerDeviceId(user.getId(), request.deviceId()), null);
    }

    public OnboardingStatusResponse onboardingStatus(CoRydUserDetails principal, String bearerToken) {
        User user = userRepository.findById(principal.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("Account not found"));

        boolean phoneVerified = user.getPhoneVerifiedAt() != null;
        boolean passwordSet = user.getPasswordHash() != null;
        ProfileServiceClient.ProfileStatus profile = profileServiceClient.profileStatus(principal.getActiveRole(), bearerToken);
        boolean termsAccepted = user.getTermsAcceptedAt() != null;

        return new OnboardingStatusResponse(phoneVerified, passwordSet, profile.exists(), termsAccepted,
                profile.identityVerified(), profile.identityVerificationStatus());
    }

    /** The one place a logged-in user's own phone number is available - profile-service's DriverProfile/RiderProfile never store it, and the only other place it lives (GET /internal/users/{userId}/contact) is service-to-service only, not reachable with a user's own JWT. */
    public UserContactResponse getMyContact(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found"));
        return new UserContactResponse(user.getId(), Names.join(user.getFirstName(), user.getLastName()), user.getFirstName(), user.getLastName(), user.getPhone());
    }

    /**
     * Exchanges a refresh token for a new session, rotating the token as it goes.
     *
     * <p>Rotation is what makes a stolen token detectable, but naive rotation has a failure mode
     * that matters a great deal more once a fingerprint is sitting in front of this call. The old
     * implementation revoked the presented token before the replacement had reached the client, so
     * a response lost in transit - an ordinary event on a mobile connection - destroyed the only
     * credential the app had. The user saw themselves signed out for no reason; with biometrics
     * enabled they would read the same event as "my fingerprint stopped working", and would have
     * no way to know the network was at fault.
     *
     * <p>So a spent token is not simply an error. Within {@link #REFRESH_REPLAY_GRACE} of being
     * rotated it is treated as that lost response and handed the same replacement again, which is
     * safe because the replacement is unchanged and still bound to the same device. Outside that
     * window it is treated as what it more likely is - a token someone else kept a copy of - and
     * the entire family descended from that sign-in is revoked. Both the thief and the real user
     * are signed out, which is the correct outcome: it is the only signal available that the
     * lineage is compromised, and a forced re-login is a far smaller cost than a silent
     * co-existing session.
     */
    @Transactional
    public AuthResponse refresh(RefreshTokenRequest request) {
        RefreshToken existing = refreshTokenRepository.findByToken(request.refreshToken())
                .orElseThrow(() -> new BadRequestException("Invalid or expired refresh token"));

        if (existing.isRevoked()) {
            // A session ops ended by suspending the account: say so, rather than "sign in again" (which would fail anyway).
            userRepository.findById(existing.getUserId()).ifPresent(AuthService::requireActive);
            return handleSpentToken(existing, request.deviceId());
        }
        if (existing.getExpiresAt().isBefore(Instant.now())) {
            throw new BadRequestException("Refresh token has expired");
        }
        requireMatchingDevice(existing, request.deviceId());

        User user = userRepository.findById(existing.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("Account no longer exists"));

        AuthResponse response = issueTokens(user, existing.getActiveRole(), existing.getDeviceId(), existing.getFamilyId());

        existing.setRevoked(true);
        existing.setRotatedAt(Instant.now());
        existing.setReplacedBy(response.refreshToken());
        refreshTokenRepository.save(existing);

        touchDevice(existing.getUserId(), existing.getDeviceId());
        return response;
    }

    /**
     * A token that has already been spent. Either the client never got the rotation response, or
     * someone is replaying a copy - the gap since rotation is the only thing that separates them,
     * so it is what decides.
     */
    private AuthResponse handleSpentToken(RefreshToken spent, String presentedDeviceId) {
        boolean withinGrace = spent.getRotatedAt() != null
                && spent.getRotatedAt().isAfter(Instant.now().minus(REFRESH_REPLAY_GRACE));

        if (withinGrace && spent.getReplacedBy() != null) {
            RefreshToken replacement = refreshTokenRepository.findByTokenAndRevokedFalse(spent.getReplacedBy())
                    .orElse(null);
            if (replacement != null && replacement.getExpiresAt().isAfter(Instant.now())) {
                requireMatchingDevice(replacement, presentedDeviceId);
                User user = userRepository.findById(replacement.getUserId())
                        .orElseThrow(() -> new ResourceNotFoundException("Account no longer exists"));
                log.info("Refresh retried within grace window for user {} - re-issuing the same replacement token", user.getId());
                touchDevice(replacement.getUserId(), replacement.getDeviceId());
                // Same refresh token back, a fresh access token alongside it. Idempotent, so a
                // client that retries after a dropped response lands exactly where it would have.
                return issueAccessTokenFor(user, replacement);
            }
        }

        log.warn("Refresh token reuse detected for user {} (device {}) - revoking the whole token family",
                spent.getUserId(), spent.getDeviceId());
        tokenFamilyRevoker.revokeFamily(spent.getFamilyId());
        throw new BadRequestException("This session is no longer valid - please sign in again");
    }

    /**
     * Binding is only enforced for tokens that carry a device, so sessions predating this - and
     * clients that never send one - keep working. But once a token IS bound, a request that omits
     * the device is refused rather than waved through: treating "absent" as "matches" would make
     * the binding worth nothing to anyone holding a stolen token.
     */
    private void requireMatchingDevice(RefreshToken token, String presentedDeviceId) {
        if (token.getDeviceId() == null) {
            return;
        }
        if (!token.getDeviceId().equals(presentedDeviceId)) {
            log.warn("Refresh token presented from device {} but was issued to {} (user {}) - revoking the family",
                    presentedDeviceId, token.getDeviceId(), token.getUserId());
            tokenFamilyRevoker.revokeFamily(token.getFamilyId());
            throw new BadRequestException("This session is no longer valid - please sign in again");
        }
        userDeviceRepository.findByUserIdAndDeviceId(token.getUserId(), token.getDeviceId())
                .filter(UserDevice::isRevoked)
                .ifPresent(device -> {
                    throw new BadRequestException("This device has been signed out - please sign in again");
                });
    }

    /**
     * Records the device this session belongs to, creating the row on first sight and refreshing
     * its display details after that. A device the user explicitly signed out is un-revoked here:
     * reaching this method means they have just authenticated on it again, which is precisely the
     * act of taking it back into use.
     */
    private String registerDevice(String userId, DeviceInfo info) {
        if (info == null || !info.isPresent()) {
            return null;
        }
        UserDevice device = userDeviceRepository.findByUserIdAndDeviceId(userId, info.deviceId())
                .orElseGet(() -> UserDevice.builder().userId(userId).deviceId(info.deviceId()).build());

        if (info.deviceName() != null) {
            device.setDeviceName(info.deviceName());
        }
        if (info.platform() != null) {
            device.setPlatform(info.platform());
        }
        device.setRevoked(false);
        device.setLastSeenAt(Instant.now());
        userDeviceRepository.save(device);
        return device.getDeviceId();
    }


    /**
     * The in-app counterpart to {@link #registerDevice}: role add/switch already knows the handset
     * from the session it is running in, so only the id comes over the wire. An id that does not
     * match a live device on this account yields null - the new session is simply unbound, which
     * is no worse than the caller not having sent one, and never an error the user would have to
     * understand mid-flow.
     */
    private String registerDeviceId(String userId, String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            return null;
        }
        return userDeviceRepository.findByUserIdAndDeviceId(userId, deviceId)
                .filter(device -> !device.isRevoked())
                .map(device -> {
                    device.setLastSeenAt(Instant.now());
                    userDeviceRepository.save(device);
                    return device.getDeviceId();
                })
                .orElse(null);
    }
    /** Keeps the devices screen honest about when each phone was last actually used. */
    private void touchDevice(String userId, String deviceId) {
        if (deviceId == null) {
            return;
        }
        userDeviceRepository.findByUserIdAndDeviceId(userId, deviceId).ifPresent(device -> {
            device.setLastSeenAt(Instant.now());
            userDeviceRepository.save(device);
        });
    }

    private AuthResponse issueTokens(User user, UserRole activeRole, DeviceInfo device) {
        requireActive(user);
        return issueTokens(user, activeRole, registerDevice(user.getId(), device), null);
    }

    /**
     * @param familyId null for a genuine sign-in, which starts a new family; carried through on
     *                 rotation so every token descended from that sign-in stays identifiable as
     *                 one lineage.
     */
    private AuthResponse issueTokens(User user, UserRole activeRole, String deviceId, String familyId) {
        requireActive(user);
        if (activeRole == UserRole.OPS_ADMIN) {
            requireOpsAccess(user);
            user.setLastSeenAt(Instant.now());
            userRepository.save(user);
        }
        String accessToken = jwtUtil.generateToken(user.getId(), user.getPhone(), user.getFirstName(), user.getLastName(), activeRole,
                opsRoleFor(user, activeRole));

        String refreshTokenValue = UUID.randomUUID().toString();
        refreshTokenRepository.save(RefreshToken.builder()
                .userId(user.getId())
                .token(refreshTokenValue)
                .activeRole(activeRole)
                .deviceId(deviceId)
                .familyId(familyId == null ? UUID.randomUUID().toString() : familyId)
                .expiresAt(Instant.now().plus(REFRESH_TOKEN_TTL_DAYS, ChronoUnit.DAYS))
                .build());

        return buildAuthResponse(user, activeRole, accessToken, refreshTokenValue,
                Instant.now().plus(REFRESH_TOKEN_TTL_DAYS, ChronoUnit.DAYS));
    }

    /** A new access token against an existing, unchanged refresh token - the retry path in {@link #handleSpentToken}. */
    private AuthResponse issueAccessTokenFor(User user, RefreshToken refreshToken) {
        if (refreshToken.getActiveRole() == UserRole.OPS_ADMIN) {
            requireOpsAccess(user);
        }
        String accessToken = jwtUtil.generateToken(user.getId(), user.getPhone(), user.getFirstName(), user.getLastName(),
                refreshToken.getActiveRole(), opsRoleFor(user, refreshToken.getActiveRole()));
        return buildAuthResponse(user, refreshToken.getActiveRole(), accessToken, refreshToken.getToken(),
                refreshToken.getExpiresAt());
    }

    /** Every way in - login, OTP, refresh, role add/switch - ends here, so a suspended account gets no tokens at all. */
    private static void requireActive(User user) {
        if (!user.isActive()) {
            throw new UnauthorizedException("This account has been suspended. Contact CoRyd support.");
        }
    }

    public static final String OPS_DEACTIVATED_MESSAGE = "Your ops console access has been removed. Ask a super admin to restore it.";

    /** A super admin removed this admin's console access - no console session, whichever way in. */
    private static void requireOpsAccess(User user) {
        if (user.getOpsDeactivatedAt() != null) {
            throw new UnauthorizedException(OPS_DEACTIVATED_MESSAGE);
        }
    }

    /** The console role on an OPS_ADMIN token (none stored = SUPER_ADMIN); nothing on any other session. */
    private static OpsRole opsRoleFor(User user, UserRole activeRole) {
        return activeRole == UserRole.OPS_ADMIN ? OpsRole.effective(user.getOpsRole()) : null;
    }

    private AuthResponse buildAuthResponse(User user, UserRole activeRole, String accessToken,
                                           String refreshTokenValue, Instant refreshExpiresAt) {
        Set<String> roleNames = user.getRoles().stream().map(Enum::name).collect(Collectors.toSet());
        long expiresIn = jwtUtil.getExpirationMs() / 1000;
        long refreshExpiresIn = Math.max(0, Duration.between(Instant.now(), refreshExpiresAt).getSeconds());
        // fullName carries only the first name - the key stays for the clients already bound to it,
        // with firstName/lastName alongside it for anything that needs the surname on its own.
        OpsRole opsRole = opsRoleFor(user, activeRole);
        return new AuthResponse(user.getId(), user.getFirstName(), user.getFirstName(), user.getLastName(),
                user.getPhone(), activeRole.name(), roleNames,
                accessToken, expiresIn, refreshTokenValue, refreshExpiresIn,
                opsRole == null ? null : opsRole.name(), activeRole == UserRole.OPS_ADMIN ? user.getEmail() : null);
    }

    /** Everywhere the user is signed in, newest first. */
    public List<DeviceResponse> listDevices(String userId, String currentDeviceId) {
        return userDeviceRepository.findByUserIdAndRevokedFalseOrderByLastSeenAtDesc(userId).stream()
                .map(device -> new DeviceResponse(
                        device.getDeviceId(),
                        device.getDeviceName(),
                        device.getPlatform(),
                        device.isBiometricEnabled(),
                        device.getLastSeenAt(),
                        device.getDeviceId().equals(currentDeviceId)))
                .toList();
    }

    /**
     * Signs one device out: the row is marked revoked and every refresh token bound to it dies
     * with it. This is the answer to a lost or stolen phone, and it is why device binding is worth
     * having at all - without the binding there would be nothing to revoke, since an unbound token
     * is usable from anywhere.
     */
    @Transactional
    public void revokeDevice(String userId, String deviceId) {
        UserDevice device = userDeviceRepository.findByUserIdAndDeviceId(userId, deviceId)
                .orElseThrow(() -> new ResourceNotFoundException("No such device on this account"));

        device.setRevoked(true);
        device.setBiometricEnabled(false);
        userDeviceRepository.save(device);
        refreshTokenRepository.revokeAllForDevice(userId, deviceId);
        log.info("Device {} signed out for user {}", deviceId, userId);
    }

    /**
     * Records that the user turned fingerprint sign-in on or off for a device.
     *
     * <p>Bookkeeping, not enforcement. No fingerprint reaches this service - the platform does not
     * expose one - so this grants nothing and proves nothing. It exists so the devices screen can
     * tell the user, from any of their phones, which handsets they left biometric sign-in enabled
     * on. Never treat it as evidence a biometric check happened.
     */
    @Transactional
    public void setBiometricPreference(String userId, BiometricPreferenceRequest request) {
        UserDevice device = userDeviceRepository.findByUserIdAndDeviceId(userId, request.deviceId())
                .orElseThrow(() -> new ResourceNotFoundException("No such device on this account"));
        if (device.isRevoked()) {
            throw new BadRequestException("This device has been signed out - sign in on it again first");
        }
        device.setBiometricEnabled(request.enabled());
        userDeviceRepository.save(device);
    }
}
