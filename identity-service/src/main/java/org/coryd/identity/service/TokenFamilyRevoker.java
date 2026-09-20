package org.coryd.identity.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.identity.repository.RefreshTokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Revokes a compromised token family in its own transaction.
 *
 * <p>This exists for one reason, and it is not stylistic. Theft detection in
 * {@link AuthService#refresh} revokes the family and then throws, and the exception is a
 * RuntimeException - so Spring marks the surrounding transaction for rollback and the revoke goes
 * with it. The check would appear to work in every log line and every test that only asserts on
 * the thrown error, while in fact leaving the stolen token live. A separate REQUIRES_NEW
 * transaction commits the revoke before the caller's rollback can touch it.
 *
 * <p>It is a separate bean because {@code @Transactional} is proxy-based: calling a REQUIRES_NEW
 * method on {@code this} would silently run in the caller's transaction and reintroduce the bug.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenFamilyRevoker {

    private final RefreshTokenRepository refreshTokenRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revokeFamily(String familyId) {
        if (familyId == null) {
            return;
        }
        refreshTokenRepository.revokeFamily(familyId);
        log.warn("Revoked refresh token family {}", familyId);
    }
}
