package org.coryd.profile.repository;

import org.coryd.profile.entity.VerificationCase;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface VerificationCaseRepository extends JpaRepository<VerificationCase, String> {
    List<VerificationCase> findByDriverId(String driverId);
}
