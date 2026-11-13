package org.coryd.identity.repository;

import org.coryd.identity.entity.OpsInvite;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface OpsInviteRepository extends JpaRepository<OpsInvite, String> {

    /** Locked: two tabs accepting the same link can't both set a password. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from OpsInvite i where i.tokenHash = :hash")
    Optional<OpsInvite> lockByTokenHash(@Param("hash") String tokenHash);

    Optional<OpsInvite> findByTokenHash(String tokenHash);

    Optional<OpsInvite> findFirstByUserIdOrderByCreatedAtDesc(String userId);

    List<OpsInvite> findByUserIdInOrderByCreatedAtDesc(Collection<String> userIds);

    List<OpsInvite> findByUserIdAndUsedAtIsNullAndRevokedAtIsNull(String userId);
}
