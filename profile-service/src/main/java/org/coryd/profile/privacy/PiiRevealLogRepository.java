package org.coryd.profile.privacy;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PiiRevealLogRepository extends JpaRepository<PiiRevealLog, String> {

    List<PiiRevealLog> findByUserIdOrderByCreatedAtDesc(String userId);
}
