package org.coryd.identity.repository;

import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.coryd.common.enums.OtpPurpose;
import org.coryd.identity.entity.OtpCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;

public interface OtpCodeRepository extends JpaRepository<OtpCode, String> {

    @Modifying
    @Query("delete from OtpCode o where o.phone = :phone")
    void deleteByPhone(@Param("phone") String phone);
    Optional<OtpCode> findTopByPhoneAndPurposeAndUsedFalseOrderByCreatedAtDesc(String phone, OtpPurpose purpose);

    /** A new code replaces every earlier one for the same flow - otherwise each resend would add its own allowance of guesses. */
    @Modifying
    @Query("update OtpCode o set o.used = true where o.phone = :phone and o.purpose = :purpose and o.used = false")
    int spendUnused(@Param("phone") String phone, @Param("purpose") OtpPurpose purpose);

    /** When the last code (any flow) was sent to this phone - OtpSendLimits' cooldown. */
    @Query("select max(o.createdAt) from OtpCode o where o.phone = :phone")
    Optional<Instant> findLatestIssuedAt(@Param("phone") String phone);

    /** Codes (any flow) sent to this phone since the given time - OtpSendLimits' hourly cap. */
    @Query("select count(o) from OtpCode o where o.phone = :phone and o.createdAt > :since")
    long countIssuedSince(@Param("phone") String phone, @Param("since") Instant since);

    /** The oldest code inside the window - tells the user when the hourly cap frees up. */
    @Query("select min(o.createdAt) from OtpCode o where o.phone = :phone and o.createdAt > :since")
    Optional<Instant> findEarliestIssuedSince(@Param("phone") String phone, @Param("since") Instant since);
}
