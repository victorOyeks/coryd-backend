package org.coryd.trip.repository;

import org.coryd.common.enums.BookingStatus;
import org.coryd.trip.entity.Booking;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface BookingRepository extends JpaRepository<Booking, String> {
    List<Booking> findByRoutePostId(String routePostId);

    /** Dev route reset only - see RouteResetService. */
    long deleteByRoutePostId(String routePostId);
    List<Booking> findByRoutePostIdIn(List<String> routePostIds);
    long countByRoutePostIdInAndStatus(List<String> routePostIds, BookingStatus status);
    List<Booking> findTop5ByRiderIdOrderByCreatedAtDesc(String riderId);
    List<Booking> findByStatusAndHoldExpiresAtBefore(BookingStatus status, Instant before);

    /** Powers the Home screen's "Go back to your current trip" banner. */
    Optional<Booking> findFirstByRiderIdAndStatusInOrderByCreatedAtDesc(String riderId, List<BookingStatus> statuses);

    /** Trip History screen - search matches the "origin -> destination" snapshot, so it works without joining route_posts. */
    @Query("""
            select b from Booking b
            where b.riderId = :riderId
              and b.status in :statuses
              and (lower(b.routeSummarySnapshot) like lower(concat('%', :search, '%')) or cast(:search as string) is null)
            order by b.createdAt desc
            """)
    Page<Booking> findHistory(@Param("riderId") String riderId, @Param("statuses") List<BookingStatus> statuses,
                               @Param("search") String search, Pageable pageable);
}
