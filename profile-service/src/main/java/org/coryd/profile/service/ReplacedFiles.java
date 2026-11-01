package org.coryd.profile.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * A driver's documents are kept once approved, so ops can always look at them - until a newer upload replaces one.
 * The old file is removed then, after the save has committed, so a failed save never loses the file it still points
 * at. Best effort: a file that can't be removed is only an orphan in storage.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReplacedFiles {

    private final CloudinaryService cloudinaryService;

    /** Removes the old file when newPublicId is a different one. */
    public void replaced(String oldPublicId, String oldUrl, String newPublicId) {
        if (oldPublicId == null || oldPublicId.isBlank() || oldPublicId.equals(newPublicId)) {
            return;
        }
        Runnable delete = () -> {
            try {
                cloudinaryService.delete(oldPublicId, CloudinaryService.resourceTypeFromUrl(oldUrl));
            } catch (Exception e) {
                log.warn("Replaced file {} couldn't be removed from Cloudinary, leaving it orphaned: {}", oldPublicId, e.getMessage());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    delete.run();
                }
            });
        } else {
            delete.run();
        }
    }
}
