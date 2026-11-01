package org.coryd.profile.repository;

import org.coryd.profile.entity.RiderIndex;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

public interface RiderIndexRepository extends JpaRepository<RiderIndex, String>, JpaSpecificationExecutor<RiderIndex> {

    /** Riders with a profile but no index row yet, after a given id - the startup backfill walks them in id order, as an
     * account that turns out not to be a rider stays "missing" and would otherwise be read again and again. */
    @Query("""
            select p.userId from RiderProfile p
            where p.userId > :after and not exists (select 1 from RiderIndex i where i.riderId = p.userId)
            order by p.userId
            """)
    Slice<String> findRidersMissingFromIndex(@org.springframework.data.repository.query.Param("after") String after, Pageable pageable);

    /** Every rider with a profile, a page at a time - the nightly rebuild. */
    @Query("select p.userId from RiderProfile p order by p.userId")
    Slice<String> findAllRiderIds(Pageable pageable);
}
