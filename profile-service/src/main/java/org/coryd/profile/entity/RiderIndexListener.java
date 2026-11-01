package org.coryd.profile.entity;

import jakarta.persistence.PostPersist;
import jakarta.persistence.PostRemove;
import jakarta.persistence.PostUpdate;
import org.coryd.profile.service.RiderIndexer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;

/** On every rider profile save, the rider's rider_index row is rebuilt after commit. Injected by Spring, looked up lazily. */
public class RiderIndexListener {

    @Autowired
    private ObjectProvider<RiderIndexer> indexer;

    @PostPersist
    @PostUpdate
    @PostRemove
    void changed(RiderProfile profile) {
        RiderIndexer target = indexer == null ? null : indexer.getIfAvailable();
        if (target != null && profile.getUserId() != null) {
            target.changed(profile.getUserId());
        }
    }
}
