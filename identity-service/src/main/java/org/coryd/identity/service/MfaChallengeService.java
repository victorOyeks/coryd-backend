package org.coryd.identity.service;

import lombok.RequiredArgsConstructor;
import org.coryd.common.exception.UnauthorizedException;
import org.coryd.identity.dto.mfa.MfaChallengeResponse;
import org.coryd.identity.dto.mfa.MfaEnrollmentResponse;
import org.coryd.identity.entity.MfaChallenge;
import org.coryd.identity.entity.User;
import org.coryd.identity.repository.MfaChallengeRepository;
import org.coryd.identity.security.OpsSecurityPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

/**
 * The single-use step between an ops admin's password and their second factor. The raw token is handed out once; only its
 * SHA-256 is stored. LOGIN tokens last 5 minutes, ENROLL tokens 15 (time to install an app); five wrong codes end either.
 * Not transactional itself: every method runs in the caller's transaction (a refusal thrown from a nested transactional
 * call would otherwise mark the caller's transaction rollback-only and lose the attempt count).
 */
@Service
@RequiredArgsConstructor
public class MfaChallengeService {

    public static final String EXPIRED = "Your sign-in has expired - sign in again";
    public static final String TOO_MANY_ATTEMPTS = "Too many attempts - sign in again";
    public static final int MAX_ATTEMPTS = 5;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final MfaChallengeRepository challengeRepository;
    private final OpsSecurityPolicy policy;

    @Value("${ops.mfa.login-token-minutes:5}")
    private long loginTokenMinutes;

    @Value("${ops.mfa.enroll-token-minutes:15}")
    private long enrollTokenMinutes;

    public MfaChallengeResponse startLogin(User user) {
        Issued issued = issue(user, MfaChallenge.Purpose.LOGIN, Duration.ofMinutes(loginTokenMinutes));
        return new MfaChallengeResponse(true, issued.token(), issued.expiresAt());
    }

    public MfaEnrollmentResponse startEnroll(User user) {
        Issued issued = issue(user, MfaChallenge.Purpose.ENROLL, Duration.ofMinutes(enrollTokenMinutes));
        return new MfaEnrollmentResponse(true, issued.token(), issued.expiresAt());
    }

    /** The open challenge for this token and purpose, locked - or 401 when it's unknown, used, expired or out of attempts. */
    public MfaChallenge open(String token, MfaChallenge.Purpose purpose) {
        MfaChallenge challenge = token == null ? null
                : challengeRepository.lockByTokenHash(hash(token)).filter(c -> c.getPurpose() == purpose).orElse(null);
        if (challenge != null && challenge.getAttempts() >= MAX_ATTEMPTS) {
            throw new UnauthorizedException(TOO_MANY_ATTEMPTS);
        }
        if (challenge == null || challenge.getUsedAt() != null || !policy.now().isBefore(challenge.getExpiresAt())) {
            throw new UnauthorizedException(EXPIRED);
        }
        return challenge;
    }

    /** A wrong code: counted; the fifth ends the challenge. Returns the message for the 401. Caller must not roll back. */
    public String wrongCode(MfaChallenge challenge, String wrongMessage) {
        challenge.setAttempts(challenge.getAttempts() + 1);
        if (challenge.getAttempts() >= MAX_ATTEMPTS) {
            challenge.setUsedAt(policy.now());
        }
        challengeRepository.save(challenge);
        return challenge.getAttempts() >= MAX_ATTEMPTS ? TOO_MANY_ATTEMPTS : wrongMessage;
    }

    public void use(MfaChallenge challenge) {
        challenge.setUsedAt(policy.now());
        challengeRepository.save(challenge);
    }

    public void closeAll(String userId) {
        challengeRepository.closeAllForUser(userId, policy.now());
    }

    private Issued issue(User user, MfaChallenge.Purpose purpose, Duration ttl) {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        Instant expiresAt = policy.now().plus(ttl);
        challengeRepository.save(MfaChallenge.builder()
                .userId(user.getId())
                .tokenHash(hash(token))
                .purpose(purpose)
                .expiresAt(expiresAt)
                .build());
        return new Issued(token, expiresAt);
    }

    private record Issued(String token, Instant expiresAt) {
    }

    static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.trim().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
