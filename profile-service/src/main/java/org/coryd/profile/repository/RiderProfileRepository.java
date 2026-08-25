package org.coryd.profile.repository;

import org.coryd.profile.entity.RiderProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RiderProfileRepository extends JpaRepository<RiderProfile, String> {
    Optional<RiderProfile> findByUserId(String userId);

    List<RiderProfile> findByUserIdIn(List<String> userIds);
}
