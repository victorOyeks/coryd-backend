package org.coryd.profile.repository;

import org.coryd.common.enums.DocumentType;
import org.coryd.common.enums.VerificationStatus;
import org.coryd.profile.entity.DriverDocument;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DriverDocumentRepository extends JpaRepository<DriverDocument, String>, JpaSpecificationExecutor<DriverDocument> {
    List<DriverDocument> findByDriverId(String driverId);

    List<DriverDocument> findByDriverIdIn(List<String> driverIds);
    Optional<DriverDocument> findByDriverIdAndDocumentType(String driverId, DocumentType documentType);

    /** Past their expiry date but not yet marked expired - what the nightly job expires. */
    @Query("""
            select d from DriverDocument d
            where d.expiresOn < :today and d.status not in (org.coryd.common.enums.VerificationStatus.EXPIRED,
                                                          org.coryd.common.enums.VerificationStatus.REJECTED)
            order by d.driverId
            """)
    Slice<DriverDocument> findDueToExpire(@Param("today") LocalDate today, Pageable pageable);

    /** Expiring between today and until, still live, and not yet sent the 3-day reminder. */
    @Query("""
            select d from DriverDocument d
            where d.expiresOn between :today and :until and d.reminderSent3d = false
              and d.status not in (org.coryd.common.enums.VerificationStatus.EXPIRED, org.coryd.common.enums.VerificationStatus.REJECTED)
            order by d.id
            """)
    Slice<DriverDocument> findDueThreeDayReminder(@Param("today") LocalDate today, @Param("until") LocalDate until, Pageable pageable);

    /** The same for the 14-day reminder. */
    @Query("""
            select d from DriverDocument d
            where d.expiresOn between :today and :until and d.reminderSent14d = false
              and d.status not in (org.coryd.common.enums.VerificationStatus.EXPIRED, org.coryd.common.enums.VerificationStatus.REJECTED)
            order by d.id
            """)
    Slice<DriverDocument> findDueFourteenDayReminder(@Param("today") LocalDate today, @Param("until") LocalDate until, Pageable pageable);

    /** Drivers holding an expired document - none of them may post, which the nightly job re-tells trip-service. */
    @Query("select distinct d.driverId from DriverDocument d where d.status = org.coryd.common.enums.VerificationStatus.EXPIRED order by d.driverId")
    Slice<String> findDriverIdsWithExpiredDocuments(Pageable pageable);

    /** The 3-day reminder went - the 14-day one is spent too, so a document uploaded close to expiry gets one push, not two. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update DriverDocument d set d.reminderSent3d = true, d.reminderSent14d = true, d.lastNotifiedAt = :at, d.lastNotifiedBy = :by
            where d.id in :ids
            """)
    int markThreeDayReminderSent(@Param("ids") Collection<String> ids, @Param("at") Instant at, @Param("by") String by);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update DriverDocument d set d.reminderSent14d = true, d.lastNotifiedAt = :at, d.lastNotifiedBy = :by where d.id in :ids")
    int markFourteenDayReminderSent(@Param("ids") Collection<String> ids, @Param("at") Instant at, @Param("by") String by);

    /** Records a notification without the entity listeners - a reminder isn't a change the driver index cares about. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update DriverDocument d set d.lastNotifiedAt = :at, d.lastNotifiedBy = :by where d.id in :ids")
    int markNotified(@Param("ids") Collection<String> ids, @Param("at") Instant at, @Param("by") String by);

    /** A document only exists once uploaded (no default empty row), so status alone is enough to
     * mean "submitted and awaiting a decision" - unlike the profile-level status fields below. */
    List<DriverDocument> findByStatus(VerificationStatus status);
}
