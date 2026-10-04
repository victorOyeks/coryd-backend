package org.coryd.payment.repository;

import org.coryd.payment.entity.SavedCard;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SavedCardRepository extends JpaRepository<SavedCard, String> {
    List<SavedCard> findByRiderIdOrderByCreatedAtDesc(String riderId);

    Optional<SavedCard> findByRiderIdAndFingerprint(String riderId, String fingerprint);

    Optional<SavedCard> findByIdAndRiderId(String id, String riderId);
}
