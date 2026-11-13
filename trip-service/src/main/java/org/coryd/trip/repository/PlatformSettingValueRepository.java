package org.coryd.trip.repository;

import jakarta.persistence.LockModeType;
import org.coryd.trip.entity.PlatformSettingValue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PlatformSettingValueRepository extends JpaRepository<PlatformSettingValue, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from PlatformSettingValue s where s.key = :key")
    Optional<PlatformSettingValue> findForUpdate(@Param("key") String key);

    @Query("select s.key from PlatformSettingValue s where s.scheduledFrom is not null and s.scheduledFrom <= :now")
    List<String> findDueKeys(@Param("now") Instant now);
}
