package org.coryd.payment.repository;

import org.coryd.payment.entity.PayoutRecipient;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PayoutRecipientRepository extends JpaRepository<PayoutRecipient, String> {
    Optional<PayoutRecipient> findByDriverId(String driverId);
}
