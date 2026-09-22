package org.coryd.profile.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.enums.VerificationStatus;
import org.coryd.common.kafka.KafkaTopics;
import org.coryd.common.kafka.events.DocumentExpiringEvent;
import org.coryd.profile.entity.DriverDocument;
import org.coryd.profile.entity.Vehicle;
import org.coryd.profile.repository.DriverDocumentRepository;
import org.coryd.profile.repository.VehicleRepository;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * D-105: reminders at 14/3 days pre-expiry, auto-suspend (flip vehicle to PENDING) on expiry.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentExpiryJob {

    private final DriverDocumentRepository documentRepository;
    private final VehicleRepository vehicleRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Scheduled(cron = "0 0 2 * * *")
    @Transactional
    public void checkExpiringDocuments() {
        remind(documentRepository.findByExpiresOnBeforeAndReminderSent14dFalse(LocalDate.now().plusDays(14)), 14, true);
        remind(documentRepository.findByExpiresOnBeforeAndReminderSent3dFalse(LocalDate.now().plusDays(3)), 3, false);
        suspendExpired();
    }

    private void remind(List<DriverDocument> documents, int daysRemaining, boolean is14dPass) {
        for (DriverDocument doc : documents) {
            kafkaTemplate.send(KafkaTopics.DOCUMENT_EXPIRING, DocumentExpiringEvent.builder()
                    .driverId(doc.getDriverId())
                    .documentType(doc.getDocumentType().name())
                    .expiresOn(doc.getExpiresOn())
                    .daysRemaining(daysRemaining)
                    .build());
            if (is14dPass) {
                doc.setReminderSent14d(true);
            } else {
                doc.setReminderSent3d(true);
            }
            documentRepository.save(doc);
        }
        log.info("Sent {} document-expiry reminders at the {}-day mark", documents.size(), daysRemaining);
    }

    private void suspendExpired() {
        List<DriverDocument> expired = documentRepository.findByExpiresOnBefore(LocalDate.now());
        for (DriverDocument doc : expired) {
            // Suspending only the vehicle left the document itself reading APPROVED with no
            // reason, so the driver's app showed a green tick on the very document that got
            // them suspended and gave them nothing to re-upload against. EXPIRED + a reason
            // mirrors what the vehicle gets below; an ops REJECTED verdict is left alone so
            // its own reason survives.
            if (doc.getStatus() != VerificationStatus.REJECTED) {
                doc.setStatus(VerificationStatus.EXPIRED);
                doc.setRejectionReason("Expired on " + doc.getExpiresOn() + " - please upload a current " + doc.getDocumentType());
                documentRepository.save(doc);
            }

            List<Vehicle> vehicles = vehicleRepository.findByDriverId(doc.getDriverId());
            for (Vehicle vehicle : vehicles) {
                if (vehicle.getVerificationStatus() != VerificationStatus.REJECTED) {
                    vehicle.setVerificationStatus(VerificationStatus.EXPIRED);
                    vehicle.setRejectionReason("Auto-suspended: " + doc.getDocumentType() + " expired");
                    vehicleRepository.save(vehicle);
                }
            }
        }
        if (!expired.isEmpty()) {
            log.info("Auto-suspended vehicles for {} expired documents", expired.size());
        }
    }
}
