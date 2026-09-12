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

    /** "First ... OrderBy" rather than a plain findByBookingId - even after the unique index added
     * in 006-payment-fare-hold-booking-unique.sql, a bare single-result query would still throw
     * NonUniqueResultException against any pre-existing duplicate the migration's cleanup step
     * missed, or on a service instance still running the old code during a rolling deploy. This
     * always returns the most recent row instead of ever risking that crash. */
    Optional<PaymentTransaction> findFirstByBookingIdOrderByCreatedAtDesc(String bookingId);

    List<PaymentTransaction> findByBookingIdIn(List<String> bookingIds);

    /** Atomic upsert keyed on the booking_id unique index - a rider retrying payment (double-tap,
     * or a client retry after a flaky status check) reuses the same row instead of racing a
     * separate findFirst-then-insert-or-update against a concurrent call for the same booking.
     * The WHERE clause after DO UPDATE makes the conflict-update a no-op (0 rows affected) once the
     * existing row is already PAID, so a caller can tell "already paid" apart from "reused/updated"
     * purely from the return value, without a second read.
     *
     * The `where booking_id is not null` right after `on conflict (booking_id)` is required, not
     * optional decoration - payment_transactions_booking_id_unique (006-payment-fare-hold-booking-
     * unique.sql) is a *partial* index (booking_id is nullable, for wallet top-ups), and Postgres can
     * only infer a partial unique index for ON CONFLICT if the same predicate is repeated here.
     * Omitting it fails every call with "no unique or exclusion constraint matching the ON CONFLICT
     * specification" (SQLSTATE 42P10) even though the index genuinely exists - this call site never
     * passes a null bookingId anyway, so the predicate is always true in practice. */
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
