package org.coryd.identity.repository;

import org.coryd.identity.entity.User;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

/** Signup counts for the ops reports. A dual-role user counts under both roles, which is intended. */
public interface PlatformStatsRepository extends Repository<User, String> {

    interface SignupCounts {
        long getNewRiders();
        long getNewDrivers();
        long getTotalRiders();
        long getTotalDrivers();
    }

    @Query(value = """
            select
                count(*) filter (where r.role = 'RIDER'
                                   and u.created_at >= :from and u.created_at < :to) as newRiders,
                count(*) filter (where r.role = 'DRIVER'
                                   and u.created_at >= :from and u.created_at < :to) as newDrivers,
                count(*) filter (where r.role = 'RIDER')                             as totalRiders,
                count(*) filter (where r.role = 'DRIVER')                            as totalDrivers
            from identity.users u
            join identity.user_roles r on r.user_id = u.id
            where u.active = true
            """, nativeQuery = true)
    SignupCounts signupCounts(@Param("from") Instant from, @Param("to") Instant to);
}
