package org.coryd.trip.repository;

import jakarta.persistence.LockModeType;
import org.coryd.trip.entity.IssueReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;


public interface IssueReportRepository extends JpaRepository<IssueReport, String>, JpaSpecificationExecutor<IssueReport> {

    /** Dev route reset only - see RouteResetService. */
    long deleteByBookingIdIn(List<String> bookingIds);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from IssueReport i where i.id = :id")
    Optional<IssueReport> lockById(@Param("id") String id);

    long countByStatus(String status);

    /**
     * Other reports involving any of these people - filed by them, or filed about a trip they were on (as its rider or its
     * driver) - newest first.
     */
    @Query(value = """
            select i.* from trip.issue_reports i
            left join trip.bookings b on b.id = i.booking_id
            left join trip.route_posts r on r.id = b.route_post_id
            where i.id <> :excludeId
              and (i.reported_by in (:people) or b.rider_id in (:people) or r.driver_id in (:people))
            order by i.created_at desc
            limit :max
            """, nativeQuery = true)
    List<IssueReport> involving(@Param("people") Collection<String> people, @Param("excludeId") String excludeId, @Param("max") int max);
}
