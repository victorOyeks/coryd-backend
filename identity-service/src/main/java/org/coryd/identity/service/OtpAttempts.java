package org.coryd.identity.service;

import lombok.RequiredArgsConstructor;
import org.coryd.common.exception.BadRequestException;
import org.coryd.identity.entity.OtpCode;
import org.coryd.identity.repository.OtpCodeRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Caps wrong guesses against one OTP. A 6-digit code lives 5 minutes; with no cap it can be brute-forced in that time,
 * and the password reset it guards hands over the account. The count is written in its own transaction because the
 * caller's is about to roll back on the "Incorrect OTP" it throws.
 */
@Component
@RequiredArgsConstructor
public class OtpAttempts {

    /** Wrong codes allowed per OTP - the next request after this many needs a fresh code. */
    public static final int MAX = 5;

    static final String TOO_MANY = "Too many incorrect codes - request a new one";

    private final OtpCodeRepository otpCodeRepository;

    /** Counts a wrong code and throws the message the user sees: "Incorrect OTP", or that the code is now spent. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = BadRequestException.class)
    public void recordWrongCode(String otpId) {
        OtpCode otp = otpCodeRepository.findById(otpId).orElse(null);
        if (otp == null) {
            throw new BadRequestException("Incorrect OTP");
        }
        otp.setAttempts(otp.getAttempts() + 1);
        boolean spent = otp.getAttempts() >= MAX;
        if (spent) {
            otp.setUsed(true);
        }
        otpCodeRepository.save(otp);
        throw new BadRequestException(spent ? TOO_MANY : "Incorrect OTP");
    }
}
