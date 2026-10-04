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

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update FareHold h set h.refundDue = :amount, h.refundReason = :reason where h.id = :id")
    int setRefundDue(@Param("id") String id, @Param("amount") BigDecimal amount, @Param("reason") String reason);

    /** Clears only the amount that was retried, so a concurrent retry can't clear it twice. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update FareHold h set h.refundDue = 0 where h.id = :id and h.refundDue = :amount")
    int clearRefundDue(@Param("id") String id, @Param("amount") BigDecimal amount);

    @Query("select h.id from FareHold h where h.refundDue > 0")
    List<String> findIdsWithRefundDue();

    /** A retry never changes amount, driver or split, so DO NOTHING is enough. */
    @Modifying
    @Query(value = "insert into payment.fare_holds (id, payment_transaction_id, booking_id, driver_id, amount, released, refunded, debt_recovery, created_at, updated_at) "
            + "values (:id, :paymentTransactionId, :bookingId, :driverId, :amount, false, false, :debtRecovery, now(), now()) "
            + "on conflict (booking_id) do nothing", nativeQuery = true)
    void insertIfAbsent(@Param("id") String id, @Param("paymentTransactionId") String paymentTransactionId,
                         @Param("bookingId") String bookingId, @Param("driverId") String driverId,
                         @Param("amount") BigDecimal amount, @Param("debtRecovery") BigDecimal debtRecovery);

    /** Debt already being recovered by paid fares that haven't settled into the ledger yet - so two
     * bookings in a row don't both claim the same debt. */
    @Query("""
            select coalesce(sum(h.debtRecovery), 0) from FareHold h, PaymentTransaction t
            where t.id = h.paymentTransactionId and h.driverId = :driverId
              and h.released = false and h.refunded = false and t.status = org.coryd.common.enums.PayoutStatus.PAID
            """)
    BigDecimal outstandingDebtRecovery(@Param("driverId") String driverId);
}
