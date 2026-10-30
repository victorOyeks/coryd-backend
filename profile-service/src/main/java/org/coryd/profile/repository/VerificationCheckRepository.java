package org.coryd.profile.repository;

import org.coryd.common.enums.VerificationCheckOutcome;
import org.coryd.common.enums.VerificationCheckType;
import org.coryd.profile.entity.VerificationCheck;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface VerificationCheckRepository extends JpaRepository<VerificationCheck, String> {

    Optional<VerificationCheck> findByDriverIdAndCheckTypeAndSubjectKey(String driverId,
                                                                        VerificationCheckType checkType,
                                                                        String subjectKey);

    List<VerificationCheck> findByDriverId(String driverId);

    /** Ops' review list - approvals on a partial name match nobody has looked at yet, oldest first. */
    Page<VerificationCheck> findByOutcomeAndReviewedAtIsNullOrderByCreatedAtAsc(VerificationCheckOutcome outcome, Pageable pageable);
}
