package org.coryd.trip.repository;

import org.coryd.trip.entity.DemandAlertLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;

public interface DemandAlertLogRepository extends JpaRepository<DemandAlertLog, String> {

    long countByDriverIdAndSentOnAfter(String driverId, LocalDate after);

    /** 1 if this driver hadn't been sent one today yet - the unique key also stops two instances both sending. */
    @Modifying
    @Query(value = "insert into trip.demand_alert_log (id, driver_id, sent_on, route_count, created_at, updated_at) "
            + "values (:id, :driverId, :sentOn, :routeCount, now(), now()) on conflict (driver_id, sent_on) do nothing", nativeQuery = true)
    int claim(@Param("id") String id, @Param("driverId") String driverId, @Param("sentOn") LocalDate sentOn,
              @Param("routeCount") int routeCount);
}
