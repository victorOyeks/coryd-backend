package org.coryd.profile.repository;

import org.coryd.profile.entity.DriverIndex;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

public interface DriverIndexRepository extends JpaRepository<DriverIndex, String>, JpaSpecificationExecutor<DriverIndex> {

    /** Drivers with a profile but no index row yet - what the startup backfill fills. */
    @Query("select p.userId from DriverProfile p where not exists (select 1 from DriverIndex i where i.driverId = p.userId) order by p.userId")
    Slice<String> findDriversMissingFromIndex(Pageable pageable);

    /** Every driver with a profile, a page at a time - the nightly rebuild. */
    @Query("select p.userId from DriverProfile p order by p.userId")
    Slice<String> findAllDriverIds(Pageable pageable);
}
