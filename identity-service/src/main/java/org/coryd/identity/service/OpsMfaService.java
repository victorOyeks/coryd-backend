package org.coryd.identity.service;

import lombok.extern.slf4j.Slf4j;
import org.coryd.common.enums.AccountActionType;
import org.coryd.common.exception.BadRequestException;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.common.exception.UnauthorizedException;
import org.coryd.common.util.Names;
import org.coryd.identity.dto.AuthResponse;
import org.coryd.identity.dto.RecordAccountActionRequest.Change;
import org.coryd.identity.dto.mfa.MfaCodeRequest;
import org.coryd.identity.dto.mfa.MfaEnrollConfirmRequest;
import org.coryd.identity.dto.mfa.MfaEnrollSetupRequest;
import org.coryd.identity.dto.mfa.MfaEnrolledResponse;
import org.coryd.identity.dto.mfa.MfaSetupView;
import org.coryd.identity.dto.mfa.MfaStatusView;
import org.coryd.identity.dto.mfa.MfaVerifyRequest;
import org.coryd.identity.dto.mfa.RecoveryCodesView;
import org.coryd.identity.dto.ops.OpsAdminView;
import org.coryd.identity.dto.ops.OpsTeamActionRequest;
import org.coryd.identity.entity.MfaChallenge;
import org.coryd.identity.entity.MfaRecoveryCode;
import org.coryd.identity.entity.User;
import org.coryd.identity.repository.MfaRecoveryCodeRepository;
import org.coryd.identity.repository.UserRepository;
import org.coryd.identity.security.MfaSecretCipher;
import org.coryd.identity.security.OpsSecurityPolicy;
import org.coryd.identity.security.Totp;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Two-factor sign-in (TOTP, RFC 6238) for ops admins - riders and drivers never see it.
 * <ul>
 *   <li><b>Sign-in:</b> AuthService.opsSignIn hands out a single-use mfaToken; {@link #verifyLogin} trades it plus a code
 *   (or an unused recovery code) for the session. Five wrong codes end the token.</li>
 *   <li><b>Enrollment at sign-in</b> (OPS_MFA_REQUIRED and not set up): {@link #enrollSetup}, {@link #enrollConfirm}.</li>
 *   <li><b>Signed in</b> (through ops-service /ops/me/mfa): setup, enable, disable, new recovery codes.</li>
 *   <li><b>Super admin reset:</b> clears it, ends every session; the admin sets it up again at next sign-in.</li>
 * </ul>
 * A code is good once (the step of the last accepted code is kept) and within one 30-second step of now. Secrets are
 * encrypted at rest (MfaSecretCipher) and never logged; recovery codes are kept as SHA-256. Everything is recorded in the
 * admin's account history (category TEAM in the audit log).
 */
@Slf4j
@Service
public class OpsMfaService {

    public static final String ISSUER = "CoRyd Ops";
    public static final String WRONG_CODE = "That code didn't work - try the latest one from your app";
    public static final int RECOVERY_CODE_COUNT = 10;
    static final String MFA_RESET = "MFA_RESET";
    /** A setup shown and not confirmed is offered again for this long (so a reload doesn't invalidate a scanned QR). */
    private static final Duration PENDING_REUSE = Duration.ofMinutes(15);
    private static final String RECOVERY_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final MfaRecoveryCodeRepository recoveryCodeRepository;
    private final MfaChallengeService challenges;
    private final AuthService authService;
    private final OpsTeamService opsTeamService;
    private final AccountActionService accountActionService;
    private final SessionRevocationService sessionRevocationService;
    private final MfaSecretCipher cipher;
    private final OpsSecurityPolicy policy;
    private final TransactionTemplate tx;

    public OpsMfaService(UserRepository userRepository, MfaRecoveryCodeRepository recoveryCodeRepository,
                         MfaChallengeService challenges, AuthService authService, OpsTeamService opsTeamService,
                         AccountActionService accountActionService, SessionRevocationService sessionRevocationService,
                         MfaSecretCipher cipher, OpsSecurityPolicy policy, PlatformTransactionManager transactionManager) {
        this.userRepository = userRepository;
        this.recoveryCodeRepository = recoveryCodeRepository;
        this.challenges = challenges;
        this.authService = authService;
        this.opsTeamService = opsTeamService;
        this.accountActionService = accountActionService;
        this.sessionRevocationService = sessionRevocationService;
        this.cipher = cipher;
        this.policy = policy;
        this.tx = new TransactionTemplate(transactionManager);
    }

    private enum Factor { TOTP, RECOVERY_CODE }

    /** Either the value, or the message for a 401 thrown once the transaction (with its attempt count) has committed. */
    private record Outcome<T>(T value, String refusal) {
    }

    // ---- sign-in (no session yet) -------------------------------------------------------------------------------------

    /** POST /auth/mfa/verify - the session, or 401 (wrong code, too many attempts, expired or used token). */
    public AuthResponse verifyLogin(MfaVerifyRequest request) {
        Outcome<AuthResponse> outcome = tx.execute(status -> {
            MfaChallenge challenge = challenges.open(request.mfaToken(), MfaChallenge.Purpose.LOGIN);
            User user = userRepository.lockById(challenge.getUserId())
                    .orElseThrow(() -> new UnauthorizedException(MfaChallengeService.EXPIRED));
            if (user.getMfaEnabledAt() == null || user.getMfaSecret() == null) {
                // reset by a super admin since the password step
                challenges.use(challenge);
                return new Outcome<>(null, MfaChallengeService.EXPIRED);
            }
            Factor factor = checkSecondFactor(user, request.code(), true);
            if (factor == null) {
                return new Outcome<>(null, challenges.wrongCode(challenge, WRONG_CODE));
            }
            challenges.use(challenge);
            if (factor == Factor.RECOVERY_CODE) {
                long left = recoveryCodeRepository.countByUserIdAndUsedAtIsNull(user.getId());
                accountActionService.record(user.getId(), AccountActionType.RECOVERY_CODE_USED, user.getId(), display(user), null,
                        List.of(new Change("recoveryCodesLeft", Long.toString(left + 1), Long.toString(left))));
                log.info("Ops admin {} signed in with a recovery code ({} left)", user.getId(), left);
            }
            return new Outcome<>(authService.opsSession(user), null);
        });
        return valueOr401(outcome);
    }

    /** POST /auth/mfa/enroll/setup - the secret for the app (two-factor required, not set up yet). */
    @Transactional
    public MfaSetupView enrollSetup(MfaEnrollSetupRequest request) {
        MfaChallenge challenge = challenges.open(request.enrollToken(), MfaChallenge.Purpose.ENROLL);
        User user = userRepository.findById(challenge.getUserId())
                .orElseThrow(() -> new UnauthorizedException(MfaChallengeService.EXPIRED));
        if (user.getMfaEnabledAt() != null) {
            throw new UnauthorizedException(MfaChallengeService.EXPIRED);
        }
        return pendingSetup(user);
    }

    /** POST /auth/mfa/enroll/confirm - two-factor on, recovery codes, and the session. */
    public MfaEnrolledResponse enrollConfirm(MfaEnrollConfirmRequest request) {
        Outcome<MfaEnrolledResponse> outcome = tx.execute(status -> {
            MfaChallenge challenge = challenges.open(request.enrollToken(), MfaChallenge.Purpose.ENROLL);
            User user = userRepository.lockById(challenge.getUserId())
                    .orElseThrow(() -> new UnauthorizedException(MfaChallengeService.EXPIRED));
            if (user.getMfaEnabledAt() != null) {
                challenges.use(challenge);
                return new Outcome<>(null, MfaChallengeService.EXPIRED);
            }
            if (user.getMfaPendingSecret() == null) {
                throw new BadRequestException("Set up your authenticator app first");
            }
            long step = Totp.matchingStep(cipher.decrypt(user.getMfaPendingSecret()), clean(request.code()), policy.now(), null);
            if (step < 0) {
                return new Outcome<>(null, challenges.wrongCode(challenge, WRONG_CODE));
            }
            challenges.use(challenge);
            List<String> codes = turnOn(user, step);
            return new Outcome<>(new MfaEnrolledResponse(authService.opsSession(user), codes), null);
        });
        return valueOr401(outcome);
    }

    // ---- the signed-in admin (through ops-service) --------------------------------------------------------------------

    @Transactional(readOnly = true)
    public MfaStatusView status(String userId) {
        User user = opsTeamService.admin(userId);
        return new MfaStatusView(user.getMfaEnabledAt() != null, policy.mfaRequired(), user.getMfaEnabledAt(),
                user.getMfaEnabledAt() == null ? 0 : recoveryCodeRepository.countByUserIdAndUsedAtIsNull(userId),
                user.getMfaEnabledAt() == null && pendingUsable(user), policy.accessTokenMinutes(), policy.idleMinutes(),
                policy.absoluteHours());
    }

    /** POST /ops/me/mfa/setup - a secret to scan, pending until a code confirms it. */
    @Transactional
    public MfaSetupView setup(String userId) {
        User user = opsTeamService.admin(userId);
        if (user.getMfaEnabledAt() != null) {
            throw new BadRequestException("Two-factor sign-in is already on - turn it off first to set up a new app");
        }
        return pendingSetup(user);
    }

    /** POST /ops/me/mfa/enable - the first code from the app turns it on; the recovery codes come back once. */
    @Transactional
    public RecoveryCodesView enable(String userId, MfaCodeRequest request) {
        User user = userRepository.lockById(userId).orElseThrow(() -> new ResourceNotFoundException("Admin not found"));
        opsTeamService.admin(userId);
        if (user.getMfaEnabledAt() != null) {
            throw new BadRequestException("Two-factor sign-in is already on");
        }
        if (user.getMfaPendingSecret() == null) {
            throw new BadRequestException("Start setting up two-factor sign-in first");
        }
        long step = Totp.matchingStep(cipher.decrypt(user.getMfaPendingSecret()), clean(request.code()), policy.now(), null);
        if (step < 0) {
            throw new BadRequestException(WRONG_CODE);
        }
        return new RecoveryCodesView(turnOn(user, step));
    }

    /** POST /ops/me/mfa/disable - a current code (or recovery code) and a reason. Refused while it's required. */
    @Transactional
    public MfaStatusView disable(String userId, MfaCodeRequest request) {
        User user = userRepository.lockById(userId).orElseThrow(() -> new ResourceNotFoundException("Admin not found"));
        opsTeamService.admin(userId);
        if (user.getMfaEnabledAt() == null) {
            throw new BadRequestException("Two-factor sign-in isn't on");
        }
        if (policy.mfaRequired()) {
            throw new BadRequestException("Two-factor sign-in is required for the ops console - it can't be turned off");
        }
        String reason = request.reason() == null ? "" : request.reason().trim();
        if (reason.isEmpty()) {
            throw new BadRequestException("Give a reason for turning off two-factor sign-in");
        }
        if (checkSecondFactor(user, request.code(), true) == null) {
            throw new BadRequestException(WRONG_CODE);
        }
        clear(user);
        accountActionService.record(userId, AccountActionType.MFA_DISABLED, userId, display(user), reason,
                List.of(new Change("twoFactor", "ON", "OFF")));
        log.info("Ops admin {} turned two-factor sign-in off", userId);
        return status(userId);
    }

    /** POST /ops/me/mfa/recovery-codes - a current code from the app; the old codes stop working. */
    @Transactional
    public RecoveryCodesView regenerateRecoveryCodes(String userId, MfaCodeRequest request) {
        User user = userRepository.lockById(userId).orElseThrow(() -> new ResourceNotFoundException("Admin not found"));
        opsTeamService.admin(userId);
        if (user.getMfaEnabledAt() == null) {
            throw new BadRequestException("Two-factor sign-in isn't on");
        }
        if (checkSecondFactor(user, request.code(), false) == null) {
            throw new BadRequestException(WRONG_CODE);
        }
        List<String> codes = newRecoveryCodes(user.getId());
        accountActionService.record(userId, AccountActionType.MFA_RECOVERY_CODES_REGENERATED, userId, display(user), null, null);
        return new RecoveryCodesView(codes);
    }

    // ---- super admin -----------------------------------------------------------------------------------------------

    /** POST /ops/team/{id}/mfa/reset - cleared, every session ended; they set it up again at next sign-in. */
    @Transactional
    public OpsAdminView reset(String userId, OpsTeamActionRequest request) {
        User actor = opsTeamService.requireSuperAdmin(request.opsUserId());
        User user = opsTeamService.admin(userId);
        if (actor.getId().equals(user.getId())) {
            throw new BadRequestException("You can't reset your own two-factor sign-in - ask another super admin");
        }
        String reason = request.reason() == null ? "" : request.reason().trim();
        if (reason.isEmpty()) {
            throw new BadRequestException("Give a reason for resetting two-factor sign-in");
        }
        if (user.getMfaEnabledAt() == null && user.getMfaPendingSecret() == null) {
            throw new BadRequestException(OpsTeamService.display(user) + " hasn't set up two-factor sign-in");
        }
        boolean wasOn = user.getMfaEnabledAt() != null;
        clear(user);
        challenges.closeAll(userId);
        sessionRevocationService.endAllSessions(user, MFA_RESET);
        accountActionService.record(userId, AccountActionType.MFA_RESET, actor.getId(), OpsTeamService.name(actor, request.opsUserName()),
                reason, List.of(new Change("twoFactor", wasOn ? "ON" : "SETUP_STARTED", "OFF")));
        log.info("Two-factor sign-in of ops admin {} reset by {}", userId, actor.getId());
        return opsTeamService.get(userId);
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    private MfaSetupView pendingSetup(User user) {
        String secret;
        if (pendingUsable(user)) {
            secret = cipher.decrypt(user.getMfaPendingSecret());
        } else {
            secret = Totp.newSecret();
            user.setMfaPendingSecret(cipher.encrypt(secret));
            user.setMfaPendingAt(policy.now());
            userRepository.save(user);
        }
        String account = account(user);
        String label = encode(ISSUER) + ":" + encode(account);
        String url = "otpauth://totp/" + label + "?secret=" + secret + "&issuer=" + encode(ISSUER)
                + "&algorithm=SHA1&digits=" + Totp.DIGITS + "&period=" + Totp.STEP_SECONDS;
        return new MfaSetupView(secret, url, ISSUER, account);
    }

    private boolean pendingUsable(User user) {
        return user.getMfaPendingSecret() != null && user.getMfaPendingAt() != null
                && policy.now().isBefore(user.getMfaPendingAt().plus(PENDING_REUSE));
    }

    private List<String> turnOn(User user, long step) {
        user.setMfaSecret(user.getMfaPendingSecret());
        user.setMfaPendingSecret(null);
        user.setMfaPendingAt(null);
        user.setMfaEnabledAt(policy.now());
        user.setMfaLastStep(step);
        userRepository.save(user);
        List<String> codes = newRecoveryCodes(user.getId());
        accountActionService.record(user.getId(), AccountActionType.MFA_ENABLED, user.getId(), display(user), null,
                List.of(new Change("twoFactor", "OFF", "ON")));
        log.info("Ops admin {} turned two-factor sign-in on", user.getId());
        return codes;
    }

    private void clear(User user) {
        user.setMfaSecret(null);
        user.setMfaEnabledAt(null);
        user.setMfaPendingSecret(null);
        user.setMfaPendingAt(null);
        user.setMfaLastStep(null);
        userRepository.save(user);
        recoveryCodeRepository.deleteByUserId(user.getId());
    }

    /**
     * The factor the code proves, or null: 6 digits from the app (within a step of now, not used before - the step is then
     * remembered), or, when allowed, an unused recovery code (then spent).
     */
    private Factor checkSecondFactor(User user, String code, boolean recoveryAllowed) {
        String entered = clean(code);
        if (entered.matches("\\d{" + Totp.DIGITS + "}")) {
            long step = Totp.matchingStep(cipher.decrypt(user.getMfaSecret()), entered, policy.now(), user.getMfaLastStep());
            if (step < 0) {
                return null;
            }
            user.setMfaLastStep(step);
            userRepository.save(user);
            return Factor.TOTP;
        }
        String recovery = normalizeRecoveryCode(entered);
        if (!recoveryAllowed || recovery == null) {
            return null;
        }
        return recoveryCodeRepository.findFirstByUserIdAndCodeHashAndUsedAtIsNull(user.getId(), MfaChallengeService.hash(recovery))
                .map(found -> {
                    found.setUsedAt(policy.now());
                    recoveryCodeRepository.save(found);
                    return Factor.RECOVERY_CODE;
                })
                .orElse(null);
    }

    private List<String> newRecoveryCodes(String userId) {
        recoveryCodeRepository.deleteByUserId(userId);
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < RECOVERY_CODE_COUNT; i++) {
            String code = randomChunk() + "-" + randomChunk();
            codes.add(code);
            recoveryCodeRepository.save(MfaRecoveryCode.builder().userId(userId).codeHash(MfaChallengeService.hash(code)).build());
        }
        return codes;
    }

    private static String randomChunk() {
        StringBuilder chunk = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            chunk.append(RECOVERY_ALPHABET.charAt(RANDOM.nextInt(RECOVERY_ALPHABET.length())));
        }
        return chunk.toString();
    }

    /** "ABCD EFGH", "abcdefgh", "abcd-efgh" -> "abcd-efgh"; null if it can't be a recovery code. */
    static String normalizeRecoveryCode(String entered) {
        String plain = entered.toLowerCase(Locale.ROOT).replace("-", "");
        if (!plain.matches("[a-z0-9]{8}")) {
            return null;
        }
        return plain.substring(0, 4) + "-" + plain.substring(4);
    }

    private static String clean(String code) {
        return code == null ? "" : code.replaceAll("\\s", "");
    }

    private static String account(User user) {
        if (user.getEmail() != null) {
            return user.getEmail();
        }
        return user.getPhone() != null ? user.getPhone() : user.getId();
    }

    private static String display(User user) {
        String name = Names.join(user.getFirstName(), user.getLastName());
        return name == null || name.isBlank() ? account(user) : name;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static <T> T valueOr401(Outcome<T> outcome) {
        if (outcome == null || outcome.refusal() != null) {
            throw new UnauthorizedException(outcome == null ? MfaChallengeService.EXPIRED : outcome.refusal());
        }
        return outcome.value();
    }
}
