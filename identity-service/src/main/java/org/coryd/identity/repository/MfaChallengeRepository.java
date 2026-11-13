package org.coryd.identity.repository;

import jakarta.persistence.LockModeType;
import org.coryd.identity.entity.MfaChallenge;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface MfaChallengeRepository extends JpaRepository<MfaChallenge, String> {

    /** Locked: two tabs sending the same token can't both get a session. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from MfaChallenge c where c.tokenHash = :hash")
    Optional<MfaChallenge> lockByTokenHash(@Param("hash") String tokenHash);

    /** A reset ends every sign-in step still open for the admin. */
    @Modifying
    @Query("update MfaChallenge c set c.usedAt = :now where c.userId = :userId and c.usedAt is null")
    void closeAllForUser(@Param("userId") String userId, @Param("now") Instant now);
}
