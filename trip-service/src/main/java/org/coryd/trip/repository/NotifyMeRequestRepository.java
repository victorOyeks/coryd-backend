package org.coryd.trip.repository;

import org.coryd.trip.entity.NotifyMeRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface NotifyMeRequestRepository extends JpaRepository<NotifyMeRequest, String> {

    List<NotifyMeRequest> findByFulfilledFalseAndExpiresAtAfter(Instant now);

    /** Same Haversine radius match as RoutePostRepository.search - see the comment there. */
    @Query(value = """
            select * from trip.notify_me_requests n
            where n.fulfilled = false
              and n.expires_at > :now
              and n.desired_departure_time between :earliest and :latest
              and (6371 * acos(least(1.0, greatest(-1.0,
                    cos(radians(:originLat)) * cos(radians(n.origin_lat)) * cos(radians(n.origin_lng) - radians(:originLng))
                    + sin(radians(:originLat)) * sin(radians(n.origin_lat))
                  )))) <= :radiusKm
              and (6371 * acos(least(1.0, greatest(-1.0,
                    cos(radians(:destLat)) * cos(radians(n.destination_lat)) * cos(radians(n.destination_lng) - radians(:destLng))
                    + sin(radians(:destLat)) * sin(radians(n.destination_lat))
                  )))) <= :radiusKm
            """, nativeQuery = true)
    List<NotifyMeRequest> findMatching(@Param("now") Instant now,
                                        @Param("earliest") Instant earliest,
                                        @Param("latest") Instant latest,
                                        @Param("originLat") double originLat,
                                        @Param("originLng") double originLng,
                                        @Param("destLat") double destLat,
                                        @Param("destLng") double destLng,
                                        @Param("radiusKm") double radiusKm);
}
