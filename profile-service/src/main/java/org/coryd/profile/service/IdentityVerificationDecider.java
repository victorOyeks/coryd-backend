package org.coryd.profile.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.enums.VerificationStatus;
import org.coryd.common.kafka.KafkaTopics;
import org.coryd.common.kafka.events.IdentityVerificationChangedEvent;
import org.coryd.profile.entity.IdentityVerification;
import org.coryd.profile.repository.IdentityVerificationRepository;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * The one place a person's check is decided - by a Smile ID match or by ops. Either way any uploaded
 * selfie is deleted (an approved one is never shown again, a rejected one is replaced by the next
 * attempt) and trip-service hears the result, since it decides who may book or post.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IdentityVerificationDecider {

    public static final String SMILE_ID = "SMILE_ID";

    private final IdentityVerificationRepository identityVerificationRepository;
    private final CloudinaryService cloudinaryService;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void approve(IdentityVerification verification, String decidedBy) {
        decide(verification, VerificationStatus.APPROVED, null, decidedBy);
    }

    public void reject(IdentityVerification verification, String reason, String decidedBy) {
        decide(verification, VerificationStatus.REJECTED, reason, decidedBy);
    }

    private void decide(IdentityVerification verification, VerificationStatus status, String reason, String decidedBy) {
        Instant now = Instant.now();
        verification.setStatus(status);
        verification.setRejectionReason(reason);
        verification.setDecidedAt(now);
        verification.setDecidedBy(decidedBy);
        purgeSelfie(verification);
        identityVerificationRepository.save(verification);
        log.info("Rider {} identity check {} by {}", verification.getUserId(), status, decidedBy);

        kafkaTemplate.send(KafkaTopics.IDENTITY_VERIFICATION_CHANGED, IdentityVerificationChangedEvent.builder()
                .userId(verification.getUserId())
                .verified(status == VerificationStatus.APPROVED)
                .decidedBy(decidedBy)
                .decidedAt(now)
                .build());
    }

    /** Best effort - a Cloudinary hiccup must never undo a decision. */
    void purgeSelfie(IdentityVerification verification) {
        if (verification.getSelfiePublicId() != null) {
            try {
                cloudinaryService.delete(verification.getSelfiePublicId(), CloudinaryService.resourceTypeFromUrl(verification.getSelfieUrl()));
            } catch (Exception e) {
                log.warn("Could not delete selfie {} for rider {}: {}", verification.getSelfiePublicId(), verification.getUserId(), e.getMessage());
            }
        }
        verification.setSelfieUrl(null);
        verification.setSelfiePublicId(null);
    }
}
