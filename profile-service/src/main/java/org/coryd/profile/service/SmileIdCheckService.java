package org.coryd.profile.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.enums.UserRole;
import org.coryd.common.enums.VerificationCheckOutcome;
import org.coryd.common.exception.BadRequestException;
import org.coryd.profile.client.SmileIdClient;
import org.coryd.profile.config.SmileIdProperties;
import org.coryd.profile.dto.SmileIdResult;
import org.coryd.profile.dto.SmileIdSession;
import org.coryd.profile.entity.SmileIdCheck;
import org.coryd.profile.entity.SmileIdCheckType;
import org.coryd.profile.entity.SmileIdRetryReason;
import org.coryd.profile.entity.VerificationCheck;
import org.coryd.profile.repository.SmileIdCheckRepository;
import org.coryd.profile.repository.VerificationCheckRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.coryd.profile.service.SmileIdResults.blankToNull;
import static org.coryd.profile.service.SmileIdResults.lower;
import static org.coryd.profile.service.SmileIdResults.safe;

/**
 * Every Smile ID check - the NIN (NinCheck) and the driver's licence (LicenceCheck) - is opened and
 * decided here, the same way:
 * <ol>
 *   <li>start: the handler says whether the person may start; we get a short-lived token, open a job and
 *       hand the app what Smile ID's SDK needs. The person's status doesn't move until a result arrives,
 *       so an abandoned check never leaves anyone stuck.</li>
 *   <li>result (from the webhook): matched to the job; a repeat delivery, an old job, or one arriving after
 *       ops took over is ignored. Then:
 *       <ul>
 *         <li>clear - the handler checks what Smile ID read against the person: approve, or ops;</li>
 *         <li>something the person can fix (face mismatch, unreadable photo, NIN not found, not a licence)
 *             - a retry reason, status unchanged;</li>
 *         <li>Smile ID or NIMC down - "try later", no try used;</li>
 *         <li>everything else (spoof, forgery, risk, expired, a reason we don't know) - ops. Only ops rejects.</li>
 *       </ul></li>
 * </ol>
 */
@Slf4j
@Service
public class SmileIdCheckService {

    private final Map<SmileIdCheckType, SmileIdCheckHandler> handlers = new EnumMap<>(SmileIdCheckType.class);
    private final SmileIdCheckRepository smileIdCheckRepository;
    private final VerificationCheckRepository verificationCheckRepository;
    private final SmileIdClient smileIdClient;
    private final SmileIdProperties properties;
    private final ObjectMapper objectMapper;

    SmileIdCheckService(List<SmileIdCheckHandler> handlers, SmileIdCheckRepository smileIdCheckRepository,
                        VerificationCheckRepository verificationCheckRepository, SmileIdClient smileIdClient,
                        SmileIdProperties properties, ObjectMapper objectMapper) {
        handlers.forEach(h -> this.handlers.put(h.type(), h));
        this.smileIdCheckRepository = smileIdCheckRepository;
        this.verificationCheckRepository = verificationCheckRepository;
        this.smileIdClient = smileIdClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** nin is the NIN they just entered for a NIN check, null for a licence check. */
    @Transactional
    public SmileIdSession start(String userId, UserRole role, SmileIdCheckType type, String nin) {
        SmileIdCheckHandler handler = handlers.get(type);
        handler.validateStart(userId, role);
        SmileIdCheck check = smileIdCheckRepository.findByUserIdAndCheckType(userId, type)
                .orElseGet(() -> SmileIdCheck.builder().userId(userId).checkType(type).build());
        // Sandbox checks are free and only ever test the app, so they're never rationed.
        if (!properties.isSandbox() && check.getAttempts() >= SmileIdCheck.MAX_ATTEMPTS) {
            throw new BadRequestException("Too many attempts - contact CoRyd support to verify your " + handler.noun());
        }
        if (!properties.isConfigured()) {
            log.error("{} check requested by {} but Smile ID isn't configured (SMILE_ID_PARTNER_ID / SMILE_ID_API_KEY)", type, userId);
            throw new BadRequestException(capitalise(handler.noun()) + " checks are unavailable right now - try again later");
        }
        String token = smileIdClient.mintToken();

        String checkId = (type == SmileIdCheckType.NIN ? "coryd-nin-" : "coryd-dl-") + UUID.randomUUID();
        check.setCheckId(checkId);
        check.setStartedAt(Instant.now());
        check.setRetryReason(null);
        smileIdCheckRepository.save(check);
        handler.opened(userId, nin);
        log.info("{} check {} opened for {}", type, checkId, userId);

        String callbackUrl = properties.getCallbackUrl();
        return new SmileIdSession(checkId, token, properties.getPartnerId(), properties.isSandbox(),
                callbackUrl == null || callbackUrl.isBlank() ? null : callbackUrl, "NG", handler.idType(),
                Map.of(SmileIdSession.CHECK_ID_PARAM, checkId), userDetails(handler, userId, role));
    }

    /**
     * Who the check is sent to Smile ID as. Live: the person, by their profile name. Sandbox: always the test
     * identity that comes back clear, so testing never depends on real people and every check succeeds.
     */
    private SmileIdSession.UserDetails userDetails(SmileIdCheckHandler handler, String userId, UserRole role) {
        if (properties.isSandbox()) {
            SmileIdProperties.TestIdentity test = properties.getTestIdentity();
            return new SmileIdSession.UserDetails(test.getGivenNames(), test.getLastName(), test.getEmail());
        }
        IdentityVerificationService.Name name = handler.profileName(userId, role);
        return new SmileIdSession.UserDetails(name.firstName(), name.lastName(), null);
    }

    @Transactional(readOnly = true)
    public SmileIdCheck find(String userId, SmileIdCheckType type) {
        return smileIdCheckRepository.findByUserIdAndCheckType(userId, type).orElse(null);
    }

    /** A result from the webhook (signature already checked). */
    @Transactional
    public void handle(SmileIdResult result) {
        String checkId = blankToNull(result.checkId());
        String smileJobId = blankToNull(result.smileJobId());
        SmileIdCheck check = checkId == null ? null : smileIdCheckRepository.findByCheckId(checkId).orElse(null);
        if (check == null) {
            log.warn("Smile ID result (job {}, check {}) matches no current check - ignored", smileJobId, checkId);
            return;
        }
        SmileIdCheckHandler handler = handlers.get(check.getCheckType());
        String userId = check.getUserId();
        if (verificationCheckRepository.findByDriverIdAndCheckTypeAndSubjectKey(userId, handler.recordedAs(), checkId).isPresent()) {
            log.info("Smile ID result for check {} already handled - ignored", checkId);
            return;
        }
        if (!handler.awaitingResult(userId)) {
            log.info("Smile ID result for check {} arrived after {}'s {} check went to ops or was decided - ignored", checkId, userId, handler.noun());
            return;
        }

        SmileIdCheckHandler.OpsOverride override = handler.startOverride(userId);
        SmileIdDecision decision = decide(handler, userId, result);
        String summary = decision.kind() == SmileIdDecision.Kind.RETRY
                ? decision.summary() : decision.summary() + SmileIdResults.fraudNote(result) + handler.resultNote(userId);
        verificationCheckRepository.save(VerificationCheck.builder()
                .driverId(userId)
                .checkType(handler.recordedAs())
                .subjectKey(checkId)
                .outcome(decision.outcome())
                .nameMatchScore(decision.nameMatchScore())
                .providerSummary(SmileIdResults.truncate(SmileIdResults.tag(properties.isSandbox()) + summary
                        + " - Smile ID job " + (smileJobId == null ? "unknown" : smileJobId)))
                .providerReference(smileJobId)
                .rawResponse(SmileIdResults.audit(objectMapper, result))
                .build());

        check.setStartedAt(null);
        check.setRetryReason(decision.retryReason());
        if (decision.usesATry()) {
            check.setAttempts(check.getAttempts() + 1);
        }
        smileIdCheckRepository.save(check);
        log.info("{} check {} (Smile ID job {}) for {}: {} - {}", check.getCheckType(), checkId, smileJobId, userId, decision.kind(), summary);

        switch (decision.kind()) {
            case APPROVE -> handler.approve(userId, result.idFields());
            case OPS -> handler.sendToOps(userId);
            case RETRY -> { }
        }
        handler.afterResult(userId, decision);
        if (override != null) {
            handler.finishOverride(userId, decision, override);
            log.info("Smile ID result for {}'s {} overrode the ops decision made while it was out: {}", userId, handler.noun(), decision.kind());
        }
    }

    private SmileIdDecision decide(SmileIdCheckHandler handler, String userId, SmileIdResult result) {
        String status = lower(blankToNull(result.status()));
        String reason = lower(blankToNull(result.reason()));
        String message = blankToNull(result.message());
        if ("clear".equals(status)) {
            return handler.onClear(userId, result.idFields());
        }
        SmileIdDecision special = handler.onReason(status, reason, message);
        if (special != null) {
            return special;
        }
        if ("block".equals(status) && "face_verification_failed".equals(reason)) {
            return SmileIdDecision.retry(SmileIdRetryReason.FACE_MISMATCH, VerificationCheckOutcome.DETAILS_MISMATCH, true,
                    "Selfie does not match the photo on the " + (handler.type() == SmileIdCheckType.NIN ? "NIN record" : "licence"));
        }
        if ("error".equals(status)) {
            if ("image_unavailable_or_invalid".equals(reason) || "document_unclassifiable".equals(reason)
                    || "content_policy_violated".equals(reason)) {
                return SmileIdDecision.retry(SmileIdRetryReason.UNREADABLE, VerificationCheckOutcome.PROVIDER_ERROR, false,
                        "Smile ID couldn't use the photos: " + safe(message));
            }
            if (!"service_unavailable".equals(reason) && !"internal_error".equals(reason)) {
                log.error("Smile ID error {} on a {} check for {} - check the account setup: {}", reason, handler.noun(), userId, message);
            }
            return SmileIdDecision.retry(SmileIdRetryReason.TRY_LATER, VerificationCheckOutcome.PROVIDER_ERROR, false,
                    "Smile ID couldn't finish the check (" + reason + "): " + safe(message));
        }
        if ("block".equals(status) || "attention".equals(status)) {
            return SmileIdDecision.ops(VerificationCheckOutcome.DETAILS_MISMATCH, null, false,
                    "Smile ID " + ("block".equals(status) ? "blocked" : "flagged") + " this check (" + reason + "): " + safe(message));
        }
        return SmileIdDecision.ops(VerificationCheckOutcome.PROVIDER_ERROR, null, false,
                "Smile ID sent a result we couldn't read (status " + status + ")");
    }

    private static String capitalise(String word) {
        return Character.toUpperCase(word.charAt(0)) + word.substring(1);
    }
}
