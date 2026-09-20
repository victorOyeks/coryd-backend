package org.coryd.profile.repository;

import org.coryd.profile.dto.VerificationCheckType;
import org.coryd.profile.entity.VerificationCheck;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface VerificationCheckRepository extends JpaRepository<VerificationCheck, String> {

    Optional<VerificationCheck> findByDriverIdAndCheckTypeAndSubjectKey(String driverId,
                                                                        VerificationCheckType checkType,
                                                                        String subjectKey);

    List<VerificationCheck> findByDriverId(String driverId);
}
