package org.coryd.identity.repository;

import org.coryd.identity.entity.UserDevice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserDeviceRepository extends JpaRepository<UserDevice, String> {

    Optional<UserDevice> findByUserIdAndDeviceId(String userId, String deviceId);

    List<UserDevice> findByUserIdAndRevokedFalseOrderByLastSeenAtDesc(String userId);
}
