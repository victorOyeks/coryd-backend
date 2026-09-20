package org.coryd.profile.repository;

import org.coryd.common.enums.VerificationStatus;
import org.coryd.profile.entity.DriverProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DriverProfileRepository extends JpaRepository<DriverProfile, String> {
    Optional<DriverProfile> findByUserId(String userId);
    List<DriverProfile> findByUserIdIn(List<String> userIds);

    /** Government ID defaults to PENDING before anything is even uploaded, so status
     * alone can't mean "submitted and awaiting a decision" the way it does for Vehicle/DriverDocument
     * (which only exist as rows once submitted) - the URL/submittedAt check is what excludes a
     * driver who simply hasn't gotten to this step yet. */
    List<DriverProfile> findByGovernmentIdUrlIsNotNullAndGovernmentIdStatus(VerificationStatus status);
}
