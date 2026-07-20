package org.coryd.payment.repository;

import org.coryd.payment.entity.Payout;
import org.springframework.data.jpa.repository.JpaRepository;


public interface PayoutRepository extends JpaRepository<Payout, String> {
}
