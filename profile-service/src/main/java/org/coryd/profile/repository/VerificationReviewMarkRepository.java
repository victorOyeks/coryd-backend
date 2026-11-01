package org.coryd.profile.repository;

import org.coryd.common.enums.ReviewableItem;
import org.coryd.profile.entity.VerificationReviewMark;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface VerificationReviewMarkRepository extends JpaRepository<VerificationReviewMark, String> {

    List<VerificationReviewMark> findByDriverId(String driverId);

    Optional<VerificationReviewMark> findByDriverIdAndItem(String driverId, ReviewableItem item);

    void deleteByDriverIdAndItemIn(String driverId, Collection<ReviewableItem> items);
}
