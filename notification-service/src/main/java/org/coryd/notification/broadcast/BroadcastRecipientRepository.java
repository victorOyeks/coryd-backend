package org.coryd.notification.broadcast;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface BroadcastRecipientRepository extends JpaRepository<BroadcastRecipient, String> {

    @Query("select r from BroadcastRecipient r where r.broadcastId = :id and r.status = :status order by r.userId")
    List<BroadcastRecipient> batch(@Param("id") String broadcastId, @Param("status") BroadcastRecipient.Status status, Pageable pageable);

    Page<BroadcastRecipient> findByBroadcastIdOrderByUserId(String broadcastId, Pageable pageable);

    Page<BroadcastRecipient> findByBroadcastIdAndStatusOrderByUserId(String broadcastId, BroadcastRecipient.Status status, Pageable pageable);

    boolean existsByBroadcastIdAndUserId(String broadcastId, String userId);

    /** status -> count, for the broadcast's totals. */
    @Query("select r.status, count(r) from BroadcastRecipient r where r.broadcastId = :id group by r.status")
    List<Object[]> countByStatus(@Param("id") String broadcastId);

    /** channel outcome counts: [channel, outcome, count]. */
    @Query(value = """
            select 'PUSH', push, count(*) from notification.broadcast_recipients where broadcast_id = :id and push is not null group by push
            union all select 'SMS', sms, count(*) from notification.broadcast_recipients where broadcast_id = :id and sms is not null group by sms
            union all select 'EMAIL', email_outcome, count(*) from notification.broadcast_recipients where broadcast_id = :id and email_outcome is not null group by email_outcome
            union all select 'WHATSAPP', whatsapp, count(*) from notification.broadcast_recipients where broadcast_id = :id and whatsapp is not null group by whatsapp
            """, nativeQuery = true)
    List<Object[]> channelOutcomes(@Param("id") String broadcastId);
}
