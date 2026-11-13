package org.coryd.identity.repository;

import org.coryd.common.enums.AccountDeletionStatus;
import org.coryd.identity.entity.AccountDeletionRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AccountDeletionRequestRepository extends JpaRepository<AccountDeletionRequest, String> {

    Optional<AccountDeletionRequest> findFirstByUserIdAndStatus(String userId, AccountDeletionStatus status);

    Page<AccountDeletionRequest> findByStatus(AccountDeletionStatus status, Pageable pageable);
}
