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

/** Rebuilds rider_index rows for riders whose profile changed - after commit, once per rider. Never fails the save. */
@Slf4j
@Component
@RequiredArgsConstructor
public class RiderIndexer {

    private static final String PENDING = RiderIndexer.class.getName() + ".pending";

    private final RiderIndexService riderIndexService;
    private final PlatformTransactionManager transactionManager;

    public void changed(String riderId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            rebuild(Set.of(riderId));
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
        pending.add(riderId);
    }

    private void rebuild(Collection<String> riderIds) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        for (String riderId : riderIds) {
            try {
                tx.executeWithoutResult(status -> riderIndexService.refresh(riderId));
            } catch (Exception e) {
                log.warn("Could not update the rider index for {} - the nightly rebuild will: {}", riderId, e.getMessage());
            }
        }
    }
}
