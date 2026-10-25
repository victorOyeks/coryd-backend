package org.coryd.identity.repository;

import org.coryd.identity.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, String> {

    @Modifying
    @Query("delete from RefreshToken t where t.userId = :userId")
    void deleteByUserId(@Param("userId") String userId);

    /**
     * Deliberately not filtered on revoked. A spent token is evidence - it tells refresh() whether
     * this is a client retrying after a dropped response or an attacker replaying a stolen token,
     * and the two get very different answers. Filtering them out at the query would throw that away.
     */
    Optional<RefreshToken> findByToken(String token);

    Optional<RefreshToken> findByTokenAndRevokedFalse(String token);

    /** Reset Password revokes every existing session - if the password was forgotten because the account was compromised, this closes any session an attacker already holds. */
    @Modifying
    @Query("update RefreshToken t set t.revoked = true where t.userId = :userId and t.revoked = false")
    void revokeAllForUser(@Param("userId") String userId);

    /** Reuse of a spent token means the lineage is compromised: kill every token descended from that sign-in, not just the one presented. */
    @Modifying
    @Query("update RefreshToken t set t.revoked = true where t.familyId = :familyId and t.revoked = false")
    void revokeFamily(@Param("familyId") String familyId);

    /** "Sign out this device" - and the same call is what makes a lost phone safe to write off. */
    @Modifying
    @Query("update RefreshToken t set t.revoked = true where t.userId = :userId and t.deviceId = :deviceId and t.revoked = false")
    void revokeAllForDevice(@Param("userId") String userId, @Param("deviceId") String deviceId);
}
