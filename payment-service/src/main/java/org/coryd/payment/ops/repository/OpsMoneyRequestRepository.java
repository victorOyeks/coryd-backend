package org.coryd.payment.ops.repository;

import jakarta.persistence.LockModeType;
import org.coryd.payment.ops.entity.OpsMoneyRequest;
import org.coryd.payment.ops.entity.OpsRequestStatus;
import org.coryd.payment.ops.entity.OpsRequestType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface OpsMoneyRequestRepository extends JpaRepository<OpsMoneyRequest, String> {

    Optional<OpsMoneyRequest> findByIdempotencyKey(String idempotencyKey);

    /** Row lock - an approve, a reject and a cancel on the same request can't both win. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from OpsMoneyRequest r where r.id = :id")
    Optional<OpsMoneyRequest> findForUpdate(@Param("id") String id);

    /** What other open manual refunds on this booking have already claimed. */
    @Query("""
            select coalesce(sum(r.amount), 0) from OpsMoneyRequest r
            where r.bookingId = :bookingId and r.type = org.coryd.payment.ops.entity.OpsRequestType.MANUAL_REFUND
              and r.status in (org.coryd.payment.ops.entity.OpsRequestStatus.PENDING_APPROVAL,
                               org.coryd.payment.ops.entity.OpsRequestStatus.EXECUTING)
              and (:excludeId is null or r.id <> :excludeId)
            """)
    BigDecimal reservedForBooking(@Param("bookingId") String bookingId, @Param("excludeId") String excludeId);

    @Query("""
            select r from OpsMoneyRequest r
            where r.status in :statuses and r.type in :types
              and (:bookingId is null or r.bookingId = :bookingId) and (:driverId is null or r.driverId = :driverId)
            order by r.createdAt desc, r.id desc
            """)
    Page<OpsMoneyRequest> search(@Param("statuses") Collection<OpsRequestStatus> statuses, @Param("types") Collection<OpsRequestType> types,
                                 @Param("bookingId") String bookingId, @Param("driverId") String driverId, Pageable pageable);

    long countByStatus(OpsRequestStatus status);

    List<OpsMoneyRequest> findByTypeAndStatusOrderByCreatedAtDesc(OpsRequestType type, OpsRequestStatus status);

    List<OpsMoneyRequest> findByTargetIdOrderByCreatedAtAsc(String targetId);

    List<OpsMoneyRequest> findByBookingIdOrderByCreatedAtAsc(String bookingId);

    List<OpsMoneyRequest> findByTargetIdIn(Collection<String> targetIds);

    List<OpsMoneyRequest> findByRefundIdIn(Collection<String> refundIds);

    boolean existsByTargetIdAndTypeAndStatusIn(String targetId, OpsRequestType type, Collection<OpsRequestStatus> statuses);
}
