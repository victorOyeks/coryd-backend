package org.coryd.identity.service;

import org.coryd.common.exception.BadRequestException;
import org.coryd.identity.repository.OtpCodeRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Throttles how often a code is texted to one phone number. Every send (signup, resend, forgot-password) is a paid SMS,
 * and none of those endpoints needs a sign-in, so without a limit anyone could loop them against any number and run up
 * the SMS bill ("SMS pumping"). Counted per phone across every flow, from the codes already stored in otp_codes.
 * The 55s cooldown sits just under the app's 59s resend countdown, so a user who waits for the button never sees it.
 */
@Component
public class OtpSendLimits {

    private final OtpCodeRepository otpCodeRepository;
    private final Clock clock;
    private final Duration cooldown;
    private final int maxPerHour;

    @Autowired
    public OtpSendLimits(OtpCodeRepository otpCodeRepository,
                         @Value("${otp.resend-cooldown-seconds:55}") long cooldownSeconds,
                         @Value("${otp.max-per-hour:5}") int maxPerHour) {
        this(otpCodeRepository, Clock.systemUTC(), Duration.ofSeconds(cooldownSeconds), maxPerHour);
    }

    OtpSendLimits(OtpCodeRepository otpCodeRepository, Clock clock, Duration cooldown, int maxPerHour) {
        this.otpCodeRepository = otpCodeRepository;
        this.clock = clock;
        this.cooldown = cooldown;
        this.maxPerHour = maxPerHour;
    }

    /** Throws the message the user sees when a new code can't be sent to this phone yet. */
    public void checkCanSend(String phone) {
        Instant now = clock.instant();

        Instant last = otpCodeRepository.findLatestIssuedAt(phone).orElse(null);
        if (last != null && last.plus(cooldown).isAfter(now)) {
            long wait = Math.max(1, Duration.between(now, last.plus(cooldown)).toSeconds());
            throw new BadRequestException("Please wait " + wait + (wait == 1 ? " second" : " seconds") + " before requesting another code");
        }

        Instant hourAgo = now.minus(Duration.ofHours(1));
        if (otpCodeRepository.countIssuedSince(phone, hourAgo) >= maxPerHour) {
            Instant oldest = otpCodeRepository.findEarliestIssuedSince(phone, hourAgo).orElse(now);
            long minutes = Math.max(1, (Duration.between(now, oldest.plus(Duration.ofHours(1))).toSeconds() + 59) / 60);
            throw new BadRequestException("Too many codes requested for this number - try again in " + minutes
                    + (minutes == 1 ? " minute" : " minutes"));
        }
    }
}
