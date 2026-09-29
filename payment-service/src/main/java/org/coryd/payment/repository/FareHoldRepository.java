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
    /** Most-recent row, not a single-result findByBookingId - avoids NonUniqueResultException against any leftover duplicate. */
    Optional<FareHold> findFirstByBookingIdOrderByCreatedAtDesc(String bookingId);

    List<FareHold> findByBookingIdIn(List<String> bookingIds);

    /** Settles the hold exactly once - a hold is either paid out to the driver or refunded to the
     * rider, never both, and never twice when the same event arrives again. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update FareHold h set h.released = true where h.id = :id and h.released = false and h.refunded = false")
    int markReleased(@Param("id") String id);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update FareHold h set h.refunded = true where h.id = :id and h.released = false and h.refunded = false")
    int markRefunded(@Param("id") String id);

    /** Same idea as WalletRepository.insertIfAbsent - a retry never changes amount/driverId, so DO NOTHING is enough. */
    @Modifying
    @Query(value = "insert into payment.fare_holds (id, payment_transaction_id, booking_id, driver_id, amount, released, refunded, created_at, updated_at) "
            + "values (:id, :paymentTransactionId, :bookingId, :driverId, :amount, false, false, now(), now()) "
            + "on conflict (booking_id) do nothing", nativeQuery = true)
    void insertIfAbsent(@Param("id") String id, @Param("paymentTransactionId") String paymentTransactionId,
                         @Param("bookingId") String bookingId, @Param("driverId") String driverId,
                         @Param("amount") BigDecimal amount);
}
