package org.coryd.profile.repository;

import org.coryd.profile.entity.SmileIdCheck;
import org.coryd.profile.entity.SmileIdCheckType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SmileIdCheckRepository extends JpaRepository<SmileIdCheck, String> {

    Optional<SmileIdCheck> findByCheckId(String checkId);

    Optional<SmileIdCheck> findByUserIdAndCheckType(String userId, SmileIdCheckType checkType);
}
