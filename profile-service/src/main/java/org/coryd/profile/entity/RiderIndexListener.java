package org.coryd.profile.entity;

import jakarta.persistence.PostPersist;
import jakarta.persistence.PostRemove;
import jakarta.persistence.PostUpdate;
import org.coryd.profile.service.RiderIndexer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * On every save of what a rider_index row is built from - the rider profile, and the identity (NIN) check: the
 * verification, its NIN Smile ID checks, and a driver's NIN lookup on their driver profile - the rider's row is rebuilt
 * after commit. Injected by Spring, looked up lazily. Accounts that aren't riders are skipped by the rebuild.
 */
public class RiderIndexListener {

    @Autowired
    private ObjectProvider<RiderIndexer> indexer;

    @PostPersist
    @PostUpdate
    @PostRemove
    void changed(Object entity) {
        String riderId = switch (entity) {
            case RiderProfile p -> p.getUserId();
            case IdentityVerification v -> v.getUserId();
            case SmileIdCheck c -> c.getCheckType() == SmileIdCheckType.NIN || c.getCheckType() == SmileIdCheckType.NIN_LOOKUP
                    ? c.getUserId() : null;
            case DriverProfile d -> d.getUserId();
            default -> null;
        };
        RiderIndexer target = indexer == null ? null : indexer.getIfAvailable();
        if (target != null && riderId != null) {
            target.changed(riderId);
        }
    }
}
