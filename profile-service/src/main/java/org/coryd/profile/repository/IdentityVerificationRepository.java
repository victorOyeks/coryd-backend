package org.coryd.profile.repository;

import org.coryd.common.enums.VerificationStatus;
import org.coryd.profile.entity.IdentityVerification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface IdentityVerificationRepository extends JpaRepository<IdentityVerification, String> {

    Optional<IdentityVerification> findByUserId(String userId);

    /** The ops queue - oldest submission first. */
    Page<IdentityVerification> findByStatusOrderBySubmittedAtAsc(VerificationStatus status, Pageable pageable);
}
