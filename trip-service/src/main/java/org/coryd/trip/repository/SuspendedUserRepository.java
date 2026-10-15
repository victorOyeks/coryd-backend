package org.coryd.trip.repository;

import org.coryd.trip.entity.SuspendedUser;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SuspendedUserRepository extends JpaRepository<SuspendedUser, String> {
}
