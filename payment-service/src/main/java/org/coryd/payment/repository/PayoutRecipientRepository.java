package org.coryd.payment.repository;

import org.coryd.payment.entity.PayoutRecipient;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PayoutRecipientRepository extends JpaRepository<PayoutRecipient, String> {
    /** Payout Setup screen's saved-accounts list, newest first. */
    List<PayoutRecipient> findByDriverIdOrderByCreatedAtDesc(String driverId);

    /** The one account actual withdrawals go to - PayoutService.requestWithdrawal/getReceipt. */
    Optional<PayoutRecipient> findByDriverIdAndIsDefaultTrue(String driverId);

    Optional<PayoutRecipient> findByIdAndDriverId(String id, String driverId);

    boolean existsByDriverId(String driverId);

    /**
     * Clears every account's default flag before the newly-selected one is set - keeps exactly
     * one default per driver. Both this and setDefault below are bulk updates rather than
     * load-mutate-save, on purpose - a bulk update alongside a separately-loaded, dirty-checked
     * entity on the same row is a real Hibernate pitfall (re-selecting the account that was
     * already default wouldn't register as a dirty-checked change, silently leaving this bulk
     * clear as the only effective write and the driver with zero default accounts).
     */
    @Modifying
    @Query("update PayoutRecipient p set p.isDefault = false where p.driverId = :driverId")
    void clearDefaultForDriver(@Param("driverId") String driverId);

    /** Returns the number of rows updated (0 means the id doesn't exist or doesn't belong to this driver) - both checked in one statement. */
    @Modifying
    @Query("update PayoutRecipient p set p.isDefault = true where p.id = :recipientId and p.driverId = :driverId")
    int setDefault(@Param("recipientId") String recipientId, @Param("driverId") String driverId);
}
