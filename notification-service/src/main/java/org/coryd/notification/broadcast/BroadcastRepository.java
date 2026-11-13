package org.coryd.notification.broadcast;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface BroadcastRepository extends JpaRepository<Broadcast, String> {

    Page<Broadcast> findByStatusInOrderByCreatedAtDesc(Collection<Broadcast.Status> statuses, Pageable pageable);

    Page<Broadcast> findAllByOrderByCreatedAtDesc(Pageable pageable);

    @Query("select b.id from Broadcast b where b.status = org.coryd.notification.broadcast.Broadcast.Status.SCHEDULED"
            + " and b.scheduledFor <= :now order by b.scheduledFor")
    List<String> findDue(@Param("now") Instant now, Pageable pageable);

    /** Takes a due broadcast (scheduled for now or earlier) for this sender - 1 when it was still SCHEDULED, 0 when another instance took it or ops cancelled it. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Broadcast b set b.status = org.coryd.notification.broadcast.Broadcast.Status.SENDING, b.startedAt = coalesce(b.startedAt, :now),"
            + " b.updatedAt = :now where b.id = :id and b.status = org.coryd.notification.broadcast.Broadcast.Status.SCHEDULED"
            + " and b.scheduledFor <= :now")
    int claim(@Param("id") String id, @Param("now") Instant now);

    @Query("select b.status from Broadcast b where b.id = :id")
    Broadcast.Status statusOf(@Param("id") String id);

    /** Sent in the last window by anyone - the global hourly cap. */
    long countByCreatedAtAfterAndStatusNot(Instant since, Broadcast.Status status);
}
