package org.coryd.profile.repository;

import org.coryd.profile.entity.VerificationDecisionLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface VerificationDecisionLogRepository extends JpaRepository<VerificationDecisionLog, String> {

    List<VerificationDecisionLog> findByDriverIdOrderByCreatedAtDesc(String driverId);

    List<VerificationDecisionLog> findByDriverIdAndItemOrderByCreatedAtDesc(String driverId, org.coryd.common.enums.DecisionSubject item);

    java.util.Optional<VerificationDecisionLog> findFirstByDriverIdAndItemOrderByCreatedAtDesc(String driverId, org.coryd.common.enums.DecisionSubject item);
}
