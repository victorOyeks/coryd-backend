package org.coryd.identity.repository;

import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.coryd.common.enums.OtpPurpose;
import org.coryd.identity.entity.OtpCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OtpCodeRepository extends JpaRepository<OtpCode, String> {

    @Modifying
    @Query("delete from OtpCode o where o.phone = :phone")
    void deleteByPhone(@Param("phone") String phone);
    Optional<OtpCode> findTopByPhoneAndPurposeAndUsedFalseOrderByCreatedAtDesc(String phone, OtpPurpose purpose);
}
