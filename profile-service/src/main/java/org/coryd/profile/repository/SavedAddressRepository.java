package org.coryd.profile.repository;

import org.coryd.profile.entity.SavedAddress;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SavedAddressRepository extends JpaRepository<SavedAddress, String> {
    List<SavedAddress> findByRiderId(String riderId);
}
