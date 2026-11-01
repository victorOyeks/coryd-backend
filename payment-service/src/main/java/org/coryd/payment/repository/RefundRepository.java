package org.coryd.payment.repository;

import org.coryd.payment.dto.RefundKind;
import org.coryd.payment.dto.RefundStatus;
import org.coryd.payment.entity.Refund;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RefundRepository extends JpaRepository<Refund, String> {
    Optional<Refund> findFirstByBookingIdAndKindAndStatusOrderByCreatedAtDesc(String bookingId, RefundKind kind, RefundStatus status);

    List<Refund> findByBookingId(String bookingId);

    List<Refund> findByBookingIdIn(java.util.Collection<String> bookingIds);
}
