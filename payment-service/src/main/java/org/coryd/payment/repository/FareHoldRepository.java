package org.coryd.payment.repository;

import org.coryd.payment.entity.FareHold;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface FareHoldRepository extends JpaRepository<FareHold, String> {
    Optional<FareHold> findByBookingId(String bookingId);
}
