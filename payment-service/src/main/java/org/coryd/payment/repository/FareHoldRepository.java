package org.coryd.payment.repository;

import org.coryd.payment.entity.FareHold;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FareHoldRepository extends JpaRepository<FareHold, String> {
    /** "First ... OrderBy" rather than a plain findByBookingId - bookingId carries no DB unique
     * constraint (nothing ever enforced at-most-one FareHold per booking), so a bare single-result
     * query throws NonUniqueResultException the moment a booking ends up with more than one row
     * (e.g. a rider retrying payment initiation). This always returns the most recent one instead
     * of crashing. */
    Optional<FareHold> findFirstByBookingIdOrderByCreatedAtDesc(String bookingId);

    List<FareHold> findByBookingIdIn(List<String> bookingIds);
}
