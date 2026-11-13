package org.coryd.identity.repository;

import org.coryd.identity.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, String> {
    Optional<User> findByPhone(String phone);
    boolean existsByPhone(String phone);

    /** Everyone a service must refuse some tokens for - what the revocation snapshot is made of. */
    @org.springframework.data.jpa.repository.Query("select u from User u where u.tokensValidAfter is not null or u.active = false")
    java.util.List<User> findWithRevokedTokens();
}
