package org.coryd.identity.repository;

import org.coryd.identity.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, String> {
    Optional<RefreshToken> findByTokenAndRevokedFalse(String token);

    /** Reset Password revokes every existing session - if the password was forgotten because the account was compromised, this closes any session an attacker already holds. */
    @Modifying
    @Query("update RefreshToken t set t.revoked = true where t.userId = :userId and t.revoked = false")
    void revokeAllForUser(@Param("userId") String userId);
}
