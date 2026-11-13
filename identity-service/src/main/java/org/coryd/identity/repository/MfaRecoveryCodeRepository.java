package org.coryd.identity.repository;

import org.coryd.identity.entity.MfaRecoveryCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface MfaRecoveryCodeRepository extends JpaRepository<MfaRecoveryCode, String> {

    Optional<MfaRecoveryCode> findFirstByUserIdAndCodeHashAndUsedAtIsNull(String userId, String codeHash);

    long countByUserIdAndUsedAtIsNull(String userId);

    @Modifying
    @Query("delete from MfaRecoveryCode c where c.userId = :userId")
    void deleteByUserId(@Param("userId") String userId);
}
