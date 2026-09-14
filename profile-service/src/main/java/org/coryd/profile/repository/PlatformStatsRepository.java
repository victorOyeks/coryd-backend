package org.coryd.profile.repository;

import org.coryd.profile.entity.Vehicle;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import java.time.Instant;

/** Driver-supply snapshot for the ops reports - point-in-time, not range-bound. */
public interface PlatformStatsRepository extends Repository<Vehicle, String> {

    interface SupplyCounts {
        long getApprovedDrivers();
        long getAwaitingApproval();
        Instant getOldestPendingAt();
    }

    @Query(value = """
            select
                count(distinct driver_id) filter (where verification_status = 'APPROVED') as approvedDrivers,
                count(*) filter (where verification_status = 'PENDING')                   as awaitingApproval,
                min(created_at) filter (where verification_status = 'PENDING')            as oldestPendingAt
            from profile.vehicles
            """, nativeQuery = true)
    SupplyCounts supplyCounts();
}
