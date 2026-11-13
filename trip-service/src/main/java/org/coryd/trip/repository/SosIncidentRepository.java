package org.coryd.trip.repository;

import jakarta.persistence.LockModeType;
import org.coryd.common.enums.SosStatus;
import org.coryd.trip.entity.SosIncident;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;


public interface SosIncidentRepository extends JpaRepository<SosIncident, String>, JpaSpecificationExecutor<SosIncident> {

    /** Ops Console SOS list, newest first. */
    Page<SosIncident> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /** One trip's SOS - raised on the route by its driver, or on one of its bookings by a rider. */
    List<SosIncident> findByRoutePostIdOrBookingIdIn(String routePostId, java.util.Collection<String> bookingIds);

    /** Dev route reset only - see RouteResetService. */
    long deleteByBookingIdIn(List<String> bookingIds);

    /** An ops action reads and changes the incident under a row lock - two admins clicking at once can't both win. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SosIncident s where s.id = :id")
    Optional<SosIncident> lockById(@Param("id") String id);

    boolean existsBySafetyAlertId(String safetyAlertId);

    long countByStatus(SosStatus status);

    /** Still unacknowledged and recent enough to keep re-alerting. */
    List<SosIncident> findByStatusAndCreatedAtAfter(SosStatus status, Instant after);

    /**
     * Moves the incident to re-alert level {@code level} only if it's still OPEN and below it - 1 when this caller won and
     * must send the re-alert, 0 when it was acknowledged meanwhile or another instance already sent it.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update SosIncident s set s.realertLevel = :level where s.id = :id and s.realertLevel < :level and s.status = :open")
    int claimRealert(@Param("id") String id, @Param("level") int level, @Param("open") SosStatus open);
}
