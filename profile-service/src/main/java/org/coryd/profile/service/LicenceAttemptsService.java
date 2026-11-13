package org.coryd.profile.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.enums.DecisionSubject;
import org.coryd.common.enums.ReviewableItem;
import org.coryd.common.enums.UserRole;
import org.coryd.common.enums.VerificationDecisionAction;
import org.coryd.common.enums.VerificationStatus;
import org.coryd.common.exception.BadRequestException;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.common.exception.UnauthorizedException;
import org.coryd.common.kafka.KafkaTopics;
import org.coryd.common.kafka.events.VerificationItemDecidedEvent;
import org.coryd.profile.dto.LicenceAttempts;
import org.coryd.profile.entity.DriverProfile;
import org.coryd.profile.entity.SmileIdCheck;
import org.coryd.profile.entity.SmileIdCheckType;
import org.coryd.profile.entity.VerificationDecisionLog;
import org.coryd.profile.repository.DriverProfileRepository;
import org.coryd.profile.repository.SmileIdCheckRepository;
import org.coryd.profile.repository.VerificationDecisionLogRepository;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * A driver's licence-check tries: how many are used, and ops giving them back to a driver locked out of the check (5 used,
 * the app says "contact CoRyd support"). The same rules as giving back identity (NIN) tries: not for a licence that's
 * already verified, and only when tries have been used. The licence's status doesn't change. Logged in the verification
 * audit log (ATTEMPTS_RESET), and the driver is told they can verify their licence again.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LicenceAttemptsService {

    private final DriverProfileRepository driverProfileRepository;
    private final SmileIdCheckRepository smileIdCheckRepository;
    private final VerificationDecisionLogRepository decisionLogRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Transactional(readOnly = true)
    public LicenceAttempts attempts(String driverId) {
        DriverProfile profile = driverProfileRepository.findByUserId(driverId).orElse(null);
        SmileIdCheck check = smileIdCheckRepository.findByUserIdAndCheckType(driverId, SmileIdCheckType.DRIVERS_LICENCE).orElse(null);
        return view(profile == null ? null : profile.getGovernmentIdStatus(), check);
    }

    @Transactional
    public LicenceAttempts reset(String driverId, String note, String reviewer, String reviewerId, UserRole callerRole) {
        if (callerRole != UserRole.OPS_ADMIN) {
            throw new UnauthorizedException("Only an ops admin can give a driver their licence tries back");
        }
        DriverProfile profile = driverProfileRepository.findByUserId(driverId)
                .orElseThrow(() -> new ResourceNotFoundException("Driver profile not found"));
        SmileIdCheck check = smileIdCheckRepository.findByUserIdAndCheckType(driverId, SmileIdCheckType.DRIVERS_LICENCE).orElse(null);
        int used = check == null ? 0 : check.getAttempts();
        String blocked = resetBlockedReason(profile.getGovernmentIdStatus(), used);
        if (blocked != null) {
            throw new BadRequestException(blocked);
        }
        check.setAttempts(0);
        smileIdCheckRepository.save(check);
        String by = reviewer == null || reviewer.isBlank() ? "ops" : reviewer;
        String trimmed = note == null || note.isBlank() ? null : note.trim();
        decisionLogRepository.save(VerificationDecisionLog.builder()
                .driverId(driverId)
                .item(DecisionSubject.GOVERNMENT_ID)
                .action(VerificationDecisionAction.ATTEMPTS_RESET)
                .previousStatus(profile.getGovernmentIdStatus())
                .status(profile.getGovernmentIdStatus())
                .note(trimmed != null ? trimmed : used + " tries given back")
                .decidedBy(by)
                .decidedById(reviewerId)
                .build());
        kafkaTemplate.send(KafkaTopics.VERIFICATION_ITEM_DECIDED, VerificationItemDecidedEvent.builder()
                .driverId(driverId)
                .email(profile.getEmail())
                .item(ReviewableItem.GOVERNMENT_ID.name())
                .itemLabel(ReviewableItem.GOVERNMENT_ID.label())
                .approved(false)
                .reviewedBy(by)
                .decidedAt(LocalDateTime.now())
                .action(VerificationDecisionAction.ATTEMPTS_RESET.name())
                .build());
        log.info("Licence check tries for {} reset by {} ({} used)", driverId, by, used);
        return view(profile.getGovernmentIdStatus(), check);
    }

    static String resetBlockedReason(VerificationStatus licenceStatus, int attemptsUsed) {
        if (licenceStatus == VerificationStatus.APPROVED) {
            return "This driver's licence is already verified - there are no tries to give back";
        }
        if (attemptsUsed <= 0) {
            return "No tries have been used - there's nothing to reset";
        }
        return null;
    }

    private static LicenceAttempts view(VerificationStatus status, SmileIdCheck check) {
        int used = check == null ? 0 : check.getAttempts();
        String blocked = resetBlockedReason(status, used);
        return new LicenceAttempts(used, SmileIdCheck.MAX_ATTEMPTS, Math.max(0, SmileIdCheck.MAX_ATTEMPTS - used),
                used >= SmileIdCheck.MAX_ATTEMPTS, blocked == null, blocked);
    }
}
