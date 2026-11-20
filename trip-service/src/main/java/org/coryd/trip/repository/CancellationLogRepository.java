package org.coryd.trip.repository;

import org.coryd.trip.entity.CancellationLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CancellationLogRepository extends JpaRepository<CancellationLog, String>, JpaSpecificationExecutor<CancellationLog> {

    boolean existsByBookingId(String bookingId);

    Optional<CancellationLog> findFirstByBookingIdAndKind(String bookingId, CancellationLog.Kind kind);

    Optional<CancellationLog> findFirstByRoutePostIdAndKindOrderByCancelledAtDesc(String routePostId, CancellationLog.Kind kind);

    List<CancellationLog> findByRoutePostIdOrderByCancelledAtAsc(String routePostId);

    List<CancellationLog> findByParentIdIn(Collection<String> parentIds);

    /** Dev route reset only - see RouteResetService. */
    long deleteByRoutePostId(String routePostId);
}
