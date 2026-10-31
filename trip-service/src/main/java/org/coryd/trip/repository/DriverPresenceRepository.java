package org.coryd.trip.repository;

import org.coryd.trip.entity.DriverPresence;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public interface DriverPresenceRepository extends JpaRepository<DriverPresence, String> {

    /** Upsert - one row per driver, so the location never builds up a history. */
    @Modifying
    @Transactional
    @Query(value = """
            insert into trip.driver_presence (driver_id, lat, lng, seen_at, alerts_that_day)
            values (:driverId, :lat, :lng, :now, 0)
            on conflict (driver_id) do update set lat = excluded.lat, lng = excluded.lng, seen_at = excluded.seen_at
            """, nativeQuery = true)
    int record(@Param("driverId") String driverId, @Param("lat") double lat, @Param("lng") double lng, @Param("now") Instant now);

    @Modifying
    @Transactional
    @Query("update DriverPresence p set p.lat = null, p.lng = null, p.seenAt = null where p.driverId = :driverId")
    int forget(@Param("driverId") String driverId);

    /** Drivers seen since `since` who may be told again - not within the cooldown, not over today's limit. */
    @Query("""
            select p from DriverPresence p
            where p.seenAt >= :since and p.lat is not null and p.lng is not null
              and (p.lastNearbyAlertAt is null or p.lastNearbyAlertAt < :cooldownBefore)
              and (p.alertDay is null or p.alertDay <> :today or p.alertsThatDay < :maxPerDay)
            """)
    List<DriverPresence> findAlertable(@Param("since") Instant since, @Param("cooldownBefore") Instant cooldownBefore,
                                       @Param("today") LocalDate today, @Param("maxPerDay") int maxPerDay);

    /** 1 if this instance gets to tell the driver - the same conditions again, so two instances never both send. */
    @Modifying
    @Transactional
    @Query(value = """
            update trip.driver_presence
            set last_nearby_alert_at = :now,
                alerts_that_day = case when alert_day = :today then alerts_that_day + 1 else 1 end,
                alert_day = :today
            where driver_id = :driverId
              and (last_nearby_alert_at is null or last_nearby_alert_at < :cooldownBefore)
              and (alert_day is null or alert_day <> :today or alerts_that_day < :maxPerDay)
            """, nativeQuery = true)
    int claimAlert(@Param("driverId") String driverId, @Param("now") Instant now, @Param("cooldownBefore") Instant cooldownBefore,
                   @Param("today") LocalDate today, @Param("maxPerDay") int maxPerDay);

    /** Clears locations past their life; drops the row once the cooldown no longer needs it. */
    @Modifying
    @Transactional
    @Query("update DriverPresence p set p.lat = null, p.lng = null, p.seenAt = null where p.seenAt < :staleBefore")
    int clearStale(@Param("staleBefore") Instant staleBefore);

    @Modifying
    @Transactional
    @Query("delete from DriverPresence p where p.seenAt is null and (p.lastNearbyAlertAt is null or p.lastNearbyAlertAt < :before)")
    int deleteIdle(@Param("before") Instant before);
}
