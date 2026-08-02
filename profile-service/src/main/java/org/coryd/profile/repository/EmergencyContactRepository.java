package org.coryd.profile.repository;

import org.coryd.profile.entity.EmergencyContact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface EmergencyContactRepository extends JpaRepository<EmergencyContact, String> {
    List<EmergencyContact> findByRiderIdOrderByCreatedAtAsc(String riderId);
    Optional<EmergencyContact> findByRiderIdAndPrimaryTrue(String riderId);
    boolean existsByRiderId(String riderId);

    /**
     * Clears every contact's primary flag before the newly-selected one is set - keeps exactly
     * one primary per rider. Both this and setPrimary below are bulk updates rather than
     * load-mutate-save, on purpose: mixing a bulk update with a separately-loaded, dirty-checked
     * entity touching the same row is a real Hibernate pitfall - if the entity's field already
     * held the value being set (re-selecting the contact that was already primary), dirty
     * checking sees no change and silently skips writing it, leaving the bulk clear as the only
     * effective write and the rider with zero primary contacts.
     */
    @Modifying
    @Query("update EmergencyContact c set c.primary = false where c.riderId = :riderId")
    void clearPrimaryForRider(@Param("riderId") String riderId);

    /** Returns the number of rows updated (0 means the id doesn't exist or doesn't belong to this rider) - both checked in one statement. */
    @Modifying
    @Query("update EmergencyContact c set c.primary = true where c.id = :contactId and c.riderId = :riderId")
    int setPrimary(@Param("contactId") String contactId, @Param("riderId") String riderId);
}
