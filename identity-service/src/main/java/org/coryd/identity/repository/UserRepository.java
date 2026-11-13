package org.coryd.identity.repository;

import org.coryd.identity.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, String> {
    Optional<User> findByPhone(String phone);
    boolean existsByPhone(String phone);

    /** Emails are stored lower case (ops admins only). */
    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    /** Every account holding OPS_ADMIN - the admin team (a handful of people, so no paging). */
    @org.springframework.data.jpa.repository.Query("select distinct u from User u join u.roles r where r = org.coryd.common.enums.UserRole.OPS_ADMIN")
    java.util.List<User> findOpsAdmins();

    /**
     * The same, row-locked until the transaction ends: role changes and deactivations take it first, so two super admins
     * demoting each other at once can't leave nobody in charge.
     */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select u from User u where u.id in (select a.id from User a join a.roles r where r = org.coryd.common.enums.UserRole.OPS_ADMIN)")
    java.util.List<User> lockOpsAdmins();

    /** Throttled: at most one write a minute per admin, however often the console loads. */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("update User u set u.lastSeenAt = :now where u.id = :id and (u.lastSeenAt is null or u.lastSeenAt < :threshold)")
    int touchLastSeen(@org.springframework.data.repository.query.Param("id") String id,
                      @org.springframework.data.repository.query.Param("now") java.time.Instant now,
                      @org.springframework.data.repository.query.Param("threshold") java.time.Instant threshold);

    /** Everyone a service must refuse some tokens for - what the revocation snapshot is made of. */
    @org.springframework.data.jpa.repository.Query("select u from User u where u.tokensValidAfter is not null or u.active = false")
    java.util.List<User> findWithRevokedTokens();
}
