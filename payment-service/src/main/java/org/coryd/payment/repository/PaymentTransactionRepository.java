package org.coryd.payment.repository;

import org.coryd.payment.entity.PaymentTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, String> {
    Optional<PaymentTransaction> findByGatewayReference(String gatewayReference);
    Optional<PaymentTransaction> findByBookingId(String bookingId);

    List<PaymentTransaction> findByBookingIdIn(List<String> bookingIds);
}
