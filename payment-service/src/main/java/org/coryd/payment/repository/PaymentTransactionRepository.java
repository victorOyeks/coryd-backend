package org.coryd.payment.repository;

import org.coryd.payment.entity.PaymentTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, String> {
    Optional<PaymentTransaction> findByGatewayReference(String gatewayReference);

    /** "First ... OrderBy" rather than a plain findByBookingId - bookingId carries no DB unique
     * constraint (nullable, since wallet top-ups have none), so a bare single-result query throws
     * NonUniqueResultException the moment a booking ends up with more than one row (e.g. a rider
     * retrying payment initiation). This always returns the most recent one instead of crashing. */
    Optional<PaymentTransaction> findFirstByBookingIdOrderByCreatedAtDesc(String bookingId);

    List<PaymentTransaction> findByBookingIdIn(List<String> bookingIds);
}
