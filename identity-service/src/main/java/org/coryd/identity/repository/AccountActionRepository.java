package org.coryd.identity.repository;

import org.coryd.common.enums.AccountActionType;
import org.coryd.identity.entity.AccountAction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface AccountActionRepository extends JpaRepository<AccountAction, String> {

    Page<AccountAction> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);

    List<AccountAction> findByUserIdAndActionInOrderByCreatedAtAsc(String userId, Collection<AccountActionType> actions);

    @Modifying
    @Query("delete from AccountAction a where a.userId = :userId")
    void deleteByUserId(@Param("userId") String userId);
}
