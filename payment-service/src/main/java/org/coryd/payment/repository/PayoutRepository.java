package org.coryd.payment.repository;

import org.coryd.payment.entity.Payout;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PayoutRepository extends JpaRepository<Payout, String> {
    /** The webhook's correlation lookup - transfer.success/failed events carry back the reference this service generated on the /transfer call. */
    Optional<Payout> findByGatewayReference(String gatewayReference);
}
