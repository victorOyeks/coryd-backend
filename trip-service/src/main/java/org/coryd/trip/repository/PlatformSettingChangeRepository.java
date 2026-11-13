package org.coryd.trip.repository;

import org.coryd.trip.entity.PlatformSettingChange;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlatformSettingChangeRepository extends JpaRepository<PlatformSettingChange, String> {

    Page<PlatformSettingChange> findByKeyOrderByCreatedAtDesc(String key, Pageable pageable);

    Page<PlatformSettingChange> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
