package org.coryd.payment.ops.repository;

import jakarta.persistence.LockModeType;
import org.coryd.payment.entity.DriverSettlement;
import org.coryd.payment.entity.FareHold;
import org.coryd.payment.entity.PaymentTransaction;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Row locks and the one ledger write the ops money actions need on tables payment-service already owns - kept here, not
 * on those tables' own repositories, so the ops console adds to them without changing them.
 */
public interface OpsMoneyLockRepository extends Repository<PaymentTransaction, String> {

    /** Every ops refund on a booking takes this lock first, so two can never both see the same amount still refundable. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from PaymentTransaction t where t.bookingId = :bookingId")
    Optional<PaymentTransaction> lockPaymentForBooking(@Param("bookingId") String bookingId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from FareHold h where h.id = :id")
    Optional<FareHold> lockHold(@Param("id") String id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from DriverSettlement s where s.id = :id")
    Optional<DriverSettlement> lockSettlement(@Param("id") String id);

    /**
     * A partial manual refund on a seat not yet settled either way: what's left of the fare is what a later cancellation
     * refunds or a completion credits the driver - so neither counts the refunded part again.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update FareHold h set h.amount = h.amount - :amount
            where h.id = :id and h.released = false and h.refunded = false and h.amount > :amount
            """)
    int reduceHoldAmount(@Param("id") String id, @Param("amount") BigDecimal amount);
}
