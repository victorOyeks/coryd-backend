package org.coryd.payment.repository;

import org.coryd.payment.entity.FareHold;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface FareHoldRepository extends JpaRepository<FareHold, String> {
    /** "First ... OrderBy" rather than a plain findByBookingId - even after the unique index added
     * in 006-payment-fare-hold-booking-unique.sql, a bare single-result query would still throw
     * NonUniqueResultException against any pre-existing duplicate the migration's cleanup step
     * missed, or on a service instance still running the old code during a rolling deploy. This
     * always returns the most recent row instead of ever risking that crash. */
    Optional<FareHold> findFirstByBookingIdOrderByCreatedAtDesc(String bookingId);

    List<FareHold> findByBookingIdIn(List<String> bookingIds);

    /** Native "insert if absent" for the same reason WalletRepository.insertIfAbsent exists - a
     * PaymentTransaction upsert already made the payment_transactions row idempotent per booking;
     * this makes its FareHold pair idempotent too. A retry never needs to change amount/driverId,
     * so ON CONFLICT DO NOTHING (not DO UPDATE) is enough - the first attempt's row stands. */
    @Modifying
    @Query(value = "insert into payment.fare_holds (id, payment_transaction_id, booking_id, driver_id, amount, released, refunded, created_at, updated_at) "
            + "values (:id, :paymentTransactionId, :bookingId, :driverId, :amount, false, false, now(), now()) "
            + "on conflict (booking_id) do nothing", nativeQuery = true)
    void insertIfAbsent(@Param("id") String id, @Param("paymentTransactionId") String paymentTransactionId,
                         @Param("bookingId") String bookingId, @Param("driverId") String driverId,
                         @Param("amount") BigDecimal amount);
}
