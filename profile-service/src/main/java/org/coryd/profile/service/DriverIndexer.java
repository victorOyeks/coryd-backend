package org.coryd.profile.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Rebuilds driver_index rows for drivers whose records changed - once their transaction commits, so the row is built
 * from committed data, and once per driver however many saves touched them. A failure here never fails the save; the
 * nightly rebuild repairs it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DriverIndexer {

    private static final String PENDING = DriverIndexer.class.getName() + ".pending";

    private final DriverIndexService driverIndexService;
    private final PlatformTransactionManager transactionManager;

    public void changed(String driverId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            rebuild(Set.of(driverId));
            return;
        }
        @SuppressWarnings("unchecked")
        Set<String> pending = (Set<String>) TransactionSynchronizationManager.getResource(PENDING);
        if (pending == null) {
            Set<String> ids = new LinkedHashSet<>();
            TransactionSynchronizationManager.bindResource(PENDING, ids);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    TransactionSynchronizationManager.unbindResourceIfPossible(PENDING);
                    if (status == STATUS_COMMITTED) {
                        rebuild(ids);
                    }
                }
            });
            pending = ids;
        }
        pending.add(driverId);
    }

    private void rebuild(Collection<String> driverIds) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        for (String driverId : driverIds) {
            try {
                tx.executeWithoutResult(status -> driverIndexService.refresh(driverId));
            } catch (Exception e) {
                log.warn("Could not update the driver index for {} - the nightly rebuild will: {}", driverId, e.getMessage());
            }
        }
    }
}
