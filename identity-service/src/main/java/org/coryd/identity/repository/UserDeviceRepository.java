package org.coryd.identity.repository;

import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.coryd.identity.entity.UserDevice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserDeviceRepository extends JpaRepository<UserDevice, String> {

    @Modifying
    @Query("delete from UserDevice d where d.userId = :userId")
    void deleteByUserId(@Param("userId") String userId);

    Optional<UserDevice> findByUserIdAndDeviceId(String userId, String deviceId);

    List<UserDevice> findByUserIdAndRevokedFalseOrderByLastSeenAtDesc(String userId);
}
