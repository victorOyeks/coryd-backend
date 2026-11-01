package org.coryd.profile.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.profile.service.DocumentExpiryService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * D-105, every morning in Lagos: expire documents past their date (the driver is told and can't post until a current one
 * is approved), send the 3- and 14-day reminders, and re-tell trip-service which drivers hold an expired document. Each
 * step runs on its own, so one failing doesn't stop the others.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentExpiryJob {

    private final DocumentExpiryService documentExpiryService;

    @Scheduled(cron = "${document-expiry.cron:0 0 7 * * *}", zone = "Africa/Lagos")
    public void checkExpiringDocuments() {
        try {
            log.info("Document expiry: expired {} document(s)", documentExpiryService.expireDue());
        } catch (Exception e) {
            log.error("Document expiry: expiring documents failed: {}", e.getMessage(), e);
        }
        try {
            log.info("Document expiry: sent {} reminder(s)", documentExpiryService.remindDue());
        } catch (Exception e) {
            log.error("Document expiry: reminders failed: {}", e.getMessage(), e);
        }
        try {
            documentExpiryService.reassertBlocked();
        } catch (Exception e) {
            log.error("Document expiry: re-telling trip-service failed: {}", e.getMessage(), e);
        }
    }
}
