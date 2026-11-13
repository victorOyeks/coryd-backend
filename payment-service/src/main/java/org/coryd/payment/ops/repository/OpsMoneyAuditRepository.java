package org.coryd.payment.ops.repository;

import org.coryd.payment.ops.entity.OpsMoneyAudit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface OpsMoneyAuditRepository extends JpaRepository<OpsMoneyAudit, String> {

    List<OpsMoneyAudit> findByRequestIdOrderByCreatedAtAsc(String requestId);

    List<OpsMoneyAudit> findByTargetIdOrderByCreatedAtAsc(String targetId);

    @Query("""
            select a from OpsMoneyAudit a
            where (:targetId is null or a.targetId = :targetId or a.requestId = :targetId)
              and (:actorId is null or a.actorId = :actorId)
              and (:action is null or a.action = :action)
            order by a.createdAt desc, a.id desc
            """)
    Page<OpsMoneyAudit> search(@Param("targetId") String targetId, @Param("actorId") String actorId,
                               @Param("action") OpsMoneyAudit.Action action, Pageable pageable);
}
