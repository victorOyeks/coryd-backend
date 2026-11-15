package org.coryd.profile.repository;

import org.coryd.profile.entity.RiderIndex;
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

public interface RiderIndexRepository extends JpaRepository<RiderIndex, String>, JpaSpecificationExecutor<RiderIndex> {

    /** Riders with a profile but no index row yet, after a given id - the startup backfill walks them in id order, as an
     * account that turns out not to be a rider stays "missing" and would otherwise be read again and again. */
    @Query("""
            select p.userId from RiderProfile p
            where p.userId > :after and not exists (select 1 from RiderIndex i where i.riderId = p.userId)
            order by p.userId
            """)
    Slice<String> findRidersMissingFromIndex(@Param("after") String after, Pageable pageable);

    /** Every rider with a profile, a page at a time - the nightly rebuild. */
    @Query("select p.userId from RiderProfile p order by p.userId")
    Slice<String> findAllRiderIds(Pageable pageable);

    /** The row, locked until the transaction ends - every rebuild and event update reads it this way, so two writers for one
     * rider take turns instead of overwriting each other or racing to insert it. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from RiderIndex i where i.riderId = :riderId")
    Optional<RiderIndex> findForUpdate(@Param("riderId") String riderId);

    /** A bare row for a rider not indexed yet - or nothing, when another writer got there first (it waits for that writer
     * to commit). The caller then locks the row with findForUpdate and fills it in, so a race is an update, never a
     * duplicate key. */
    @Modifying
    @Query(value = """
            insert into profile.rider_index (rider_id, ref_key, directory_status, identity_status, refreshed_at)
            values (:riderId, :refKey, 'ACTIVE', 'NOT_STARTED', now())
            on conflict (rider_id) do nothing
            """, nativeQuery = true)
    int insertIfAbsent(@Param("riderId") String riderId, @Param("refKey") String refKey);
}
