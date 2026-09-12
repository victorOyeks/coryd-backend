package org.coryd.payment.repository;

import org.coryd.payment.entity.PaymentTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, String> {
    Optional<PaymentTransaction> findByGatewayReference(String gatewayReference);

    /** Most-recent row, not a single-result findByBookingId - avoids NonUniqueResultException against any leftover duplicate. */
    Optional<PaymentTransaction> findFirstByBookingIdOrderByCreatedAtDesc(String bookingId);

    List<PaymentTransaction> findByBookingIdIn(List<String> bookingIds);

    /** Upsert on the booking_id unique index so a payment retry updates the same row instead of
     * racing a check-then-insert. "where booking_id is not null" repeats the partial index's own
     * predicate (required for Postgres to infer it in ON CONFLICT); "where status <> 'PAID'" makes
     * the update a no-op (affected == 0) once the booking's already paid. */
    @Modifying
    @Query(value = """
            insert into payment.payment_transactions
                (id, booking_id, rider_id, amount, gateway_reference, status, payment_method, purpose, created_at, updated_at)
            values
                (:id, :bookingId, :riderId, :amount, :gatewayReference, :status, :paymentMethod, :purpose, now(), now())
            on conflict (booking_id) where booking_id is not null do update set
                gateway_reference = excluded.gateway_reference,
                status = excluded.status,
                payment_method = excluded.payment_method,
                amount = excluded.amount,
                updated_at = now()
            where payment.payment_transactions.status <> 'PAID'
            """, nativeQuery = true)
    int upsertByBookingId(@Param("id") String id, @Param("bookingId") String bookingId, @Param("riderId") String riderId,
                           @Param("amount") BigDecimal amount, @Param("gatewayReference") String gatewayReference,
                           @Param("status") String status, @Param("paymentMethod") String paymentMethod,
                           @Param("purpose") String purpose);
}
