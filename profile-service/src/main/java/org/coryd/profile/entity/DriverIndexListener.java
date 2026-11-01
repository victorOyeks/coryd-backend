package org.coryd.profile.entity;

import jakarta.persistence.PostPersist;
import jakarta.persistence.PostRemove;
import jakarta.persistence.PostUpdate;
import org.coryd.profile.service.DriverIndexer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * On every driver profile, vehicle, document or licence check save, the driver's driver_index row is rebuilt after
 * commit - so no code path that changes a driver can forget to. Hibernate creates this through Spring, which injects
 * the indexer; it's looked up only when needed, as the indexer itself depends on the repositories being built.
 */
public class DriverIndexListener {

    @Autowired
    private ObjectProvider<DriverIndexer> indexer;

    @PostPersist
    @PostUpdate
    @PostRemove
    void changed(Object entity) {
        String driverId = switch (entity) {
            case DriverProfile p -> p.getUserId();
            case Vehicle v -> v.getDriverId();
            case DriverDocument d -> d.getDriverId();
            case SmileIdCheck c -> c.getCheckType() == SmileIdCheckType.DRIVERS_LICENCE ? c.getUserId() : null;
            default -> null;
        };
        DriverIndexer target = indexer == null ? null : indexer.getIfAvailable();
        if (target != null && driverId != null) {
            target.changed(driverId);
        }
    }
}
