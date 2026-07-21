package org.coryd.identity.dto;

/**
 * roleAdditionRequired = true means the phone already has an account under a
 * different role - no OTP was sent here, the client must call
 * POST /auth/roles/challenge (password + phone) instead of continuing the
 * plain signup OTP flow.
 */
public record SignupResponse(
        boolean roleAdditionRequired
) {
}
