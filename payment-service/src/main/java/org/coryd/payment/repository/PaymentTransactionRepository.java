package org.coryd.payment.repository;

import org.coryd.payment.entity.PaymentTransaction;
import org.coryd.common.enums.PayoutStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Collection;

public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, String> {

    /** Compare-and-set on status - Paystack resends webhooks, so 0 means this one was already handled. */
    @Modifying(flushAutomatically = true)
    @Query("update PaymentTransaction t set t.status = :to, t.updatedAt = CURRENT_TIMESTAMP where t.id = :id and t.status in :from")
    int transitionStatus(@Param("id") String id, @Param("from") Collection<PayoutStatus> from, @Param("to") PayoutStatus to);

    @Modifying(flushAutomatically = true)
    @Query("update PaymentTransaction t set t.gatewayReference = :reference, t.updatedAt = CURRENT_TIMESTAMP where t.id = :id")
    int adoptReference(@Param("id") String id, @Param("reference") String reference);
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
                (id, booking_id, rider_id, amount, gateway_reference, authorization_url, status, payment_method, purpose, created_at, updated_at)
            values
                (:id, :bookingId, :riderId, :amount, :gatewayReference, :authorizationUrl, :status, :paymentMethod, :purpose, now(), now())
            on conflict (booking_id) where booking_id is not null do update set
                gateway_reference = excluded.gateway_reference,
                authorization_url = excluded.authorization_url,
                status = excluded.status,
                payment_method = excluded.payment_method,
                amount = excluded.amount,
                updated_at = now()
            where payment.payment_transactions.status <> 'PAID'
            """, nativeQuery = true)
    int upsertByBookingId(@Param("id") String id, @Param("bookingId") String bookingId, @Param("riderId") String riderId,
                           @Param("amount") BigDecimal amount, @Param("gatewayReference") String gatewayReference,
                           @Param("authorizationUrl") String authorizationUrl,
                           @Param("status") String status, @Param("paymentMethod") String paymentMethod,
                           @Param("purpose") String purpose);
}
