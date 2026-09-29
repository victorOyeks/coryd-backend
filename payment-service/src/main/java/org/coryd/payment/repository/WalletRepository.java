package org.coryd.payment.repository;

import org.coryd.payment.entity.Wallet;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface WalletRepository extends JpaRepository<Wallet, String> {
    Optional<Wallet> findByUserId(String userId);

    /** Row lock for every balance change - a debit and a top-up credit landing together would
     * otherwise each write back a balance computed from the same stale read. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Wallet w where w.userId = :userId")
    Optional<Wallet> findForUpdate(@Param("userId") String userId);

    /**
     * Native "insert if absent" for WalletService.getOrCreateWallet()'s race: two concurrent
     * first-ever requests for the same userId (e.g. a screen firing GET /balance and
     * GET /transactions together) can both see no wallet from findByUserId before either commits,
     * and both try to insert - a plain save() there throws a unique-constraint violation on
     * whichever one loses, and Postgres poisons that transaction, so nothing inside it can even
     * retry the lookup afterward. ON CONFLICT DO NOTHING never throws either way, so the loser
     * just no-ops here and the subsequent findByUserId picks up the winner's row.
     */
    @Modifying
    @Query(value = "insert into payment.wallets (id, user_id, created_at, updated_at) "
            + "values (:id, :userId, now(), now()) on conflict (user_id) do nothing", nativeQuery = true)
    void insertIfAbsent(@Param("id") String id, @Param("userId") String userId);
}
