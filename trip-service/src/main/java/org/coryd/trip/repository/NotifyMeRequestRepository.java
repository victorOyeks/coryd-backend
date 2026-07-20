package org.coryd.trip.repository;

import org.coryd.trip.entity.NotifyMeRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface NotifyMeRequestRepository extends JpaRepository<NotifyMeRequest, String> {

    List<NotifyMeRequest> findByFulfilledFalseAndExpiresAtAfter(Instant now);

    @Query("""
            select n from NotifyMeRequest n
            where n.fulfilled = false
              and n.expiresAt > :now
              and n.desiredDepartureTime between :earliest and :latest
              and n.originLat between :originLatMin and :originLatMax
              and n.originLng between :originLngMin and :originLngMax
              and n.destinationLat between :destLatMin and :destLatMax
              and n.destinationLng between :destLngMin and :destLngMax
            """)
    List<NotifyMeRequest> findMatching(@Param("now") Instant now,
                                        @Param("earliest") Instant earliest,
                                        @Param("latest") Instant latest,
                                        @Param("originLatMin") double originLatMin,
                                        @Param("originLatMax") double originLatMax,
                                        @Param("originLngMin") double originLngMin,
                                        @Param("originLngMax") double originLngMax,
                                        @Param("destLatMin") double destLatMin,
                                        @Param("destLatMax") double destLatMax,
                                        @Param("destLngMin") double destLngMin,
                                        @Param("destLngMax") double destLngMax);
}
