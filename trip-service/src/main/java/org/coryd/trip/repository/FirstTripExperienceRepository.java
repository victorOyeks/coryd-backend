package org.coryd.trip.repository;

import org.coryd.trip.entity.FirstTripExperience;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface FirstTripExperienceRepository extends JpaRepository<FirstTripExperience, String> {

    boolean existsByUserIdAndRole(String userId, FirstTripExperience.Role role);

    Optional<FirstTripExperience> findByUserIdAndRole(String userId, FirstTripExperience.Role role);

    /** Seats this rider has ridden to the end. */
    @Query("select count(b) from Booking b where b.riderId = :riderId and b.status = org.coryd.common.enums.BookingStatus.COMPLETED")
    long countCompletedRides(@Param("riderId") String riderId);

    /** Completed routes this driver actually carried someone on - a route everyone skipped isn't a first drive. */
    @Query("""
            select count(r) from RoutePost r where r.driverId = :driverId and r.status = org.coryd.common.enums.RouteStatus.COMPLETED
            and exists (select 1 from Booking b where b.routePostId = r.id and b.status = org.coryd.common.enums.BookingStatus.COMPLETED)""")
    long countCompletedDrivesWithRiders(@Param("driverId") String driverId);

    /** Shared stories for the console, newest first; role / consent null means any. */
    @Query("""
            select e from FirstTripExperience e where e.status = org.coryd.trip.entity.FirstTripExperience.Status.SUBMITTED
            and (:role is null or e.role = :role) and (:consent is null or e.consentToShare = :consent)
            order by e.respondedAt desc, e.id asc""")
    Page<FirstTripExperience> findShared(@Param("role") FirstTripExperience.Role role, @Param("consent") Boolean consent, Pageable pageable);
}
