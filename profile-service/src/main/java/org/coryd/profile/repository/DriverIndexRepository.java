package org.coryd.profile.repository;

import org.coryd.profile.entity.DriverIndex;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.Optional;

public interface DriverIndexRepository extends JpaRepository<DriverIndex, String>, JpaSpecificationExecutor<DriverIndex> {

    /** Drivers with a profile but no index row yet, after a given id - what the startup backfill fills, walked in id order so
     * a driver whose row fails to build is passed over rather than read again and again. */
    @Query("""
            select p.userId from DriverProfile p
            where p.userId > :after and not exists (select 1 from DriverIndex i where i.driverId = p.userId)
            order by p.userId
            """)
    Slice<String> findDriversMissingFromIndex(@Param("after") String after, Pageable pageable);

    /** Every driver with a profile, a page at a time - the nightly rebuild. */
    @Query("select p.userId from DriverProfile p order by p.userId")
    Slice<String> findAllDriverIds(Pageable pageable);

    /** The row, locked until the transaction ends - see RiderIndexRepository.findForUpdate. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from DriverIndex i where i.driverId = :driverId")
    Optional<DriverIndex> findForUpdate(@Param("driverId") String driverId);

    /** A bare row for a driver not indexed yet, or nothing when another writer got there first - see
     * RiderIndexRepository.insertIfAbsent. */
    @Modifying
    @Query(value = """
            insert into profile.driver_index (driver_id, ref_key, directory_status, resubmitted, in_review, refreshed_at)
            values (:driverId, :refKey, 'PENDING', false, false, now())
            on conflict (driver_id) do nothing
            """, nativeQuery = true)
    int insertIfAbsent(@Param("driverId") String driverId, @Param("refKey") String refKey);
}
