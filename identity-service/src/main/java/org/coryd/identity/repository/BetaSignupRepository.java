package org.coryd.identity.repository;

import org.coryd.identity.entity.BetaSignup;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface BetaSignupRepository extends JpaRepository<BetaSignup, String> {

    Optional<BetaSignup> findByWhatsappNumber(String whatsappNumber);

    /** Ops list - any filter left null matches everything; search looks at name, number and route. */
    @Query("""
            select b from BetaSignup b
            where (:status is null or b.status = :status)
              and (:willMostly is null or b.willMostly = :willMostly)
              and (:device is null or b.device = :device)
              and (:search is null or lower(b.name) like :search or b.whatsappNumber like :search or lower(b.usualRoute) like :search)
            order by b.createdAt desc
            """)
    Page<BetaSignup> search(@Param("status") BetaSignup.Status status, @Param("willMostly") BetaSignup.Intent willMostly,
                            @Param("device") BetaSignup.Device device, @Param("search") String search, Pageable pageable);

    interface Summary {
        long getTotal();
        long getRiders();
        long getDrivers();
        long getIphone();
        long getAndroid();
        long getWaiting();
        long getInvited();
        long getJoined();
    }

    @Query(value = """
            select count(*) as total,
                   count(*) filter (where will_mostly = 'RIDE') as riders,
                   count(*) filter (where will_mostly = 'DRIVE') as drivers,
                   count(*) filter (where device = 'IPHONE') as iphone,
                   count(*) filter (where device = 'ANDROID') as android,
                   count(*) filter (where status = 'WAITING') as waiting,
                   count(*) filter (where status = 'INVITED') as invited,
                   count(*) filter (where joined_at is not null) as joined
            from identity.beta_signups
            """, nativeQuery = true)
    Summary summary();
}
