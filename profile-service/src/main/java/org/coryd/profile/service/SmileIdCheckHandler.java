package org.coryd.profile.service;

import org.coryd.common.enums.UserRole;
import org.coryd.common.enums.VerificationCheckType;
import org.coryd.profile.dto.SmileIdResult;
import org.coryd.profile.entity.SmileIdCheckType;

/**
 * What differs between Smile ID checks - who may start one, what a clear result must also match, and
 * where the outcome is written. Everything else (opening the job, matching a result to it, retries, the
 * ops queue, the audit trail) is SmileIdCheckService's, the same for every check.
 */
interface SmileIdCheckHandler {

    SmileIdCheckType type();

    /** The Smile ID id_type the app's SDK submits. */
    String idType();

    /** How the result is filed on verification_checks. */
    VerificationCheckType recordedAs();

    /** "identity" or "licence" - for the messages people see. */
    String noun();

    /** Throws a BadRequestException, worded for the person, when they can't start a check right now. */
    void validateStart(String userId, UserRole role);

    /** A check was just opened - put the person's record into the state it waits in. nin is null for a licence. */
    void opened(String userId, String nin);

    /** The name as on their profile for this check - what the SDK sends on live. */
    IdentityVerificationService.Name profileName(String userId, UserRole role);

    /** False once ops has it or has decided - a late result never overrides that, unless ops decided while it was still out. */
    boolean awaitingResult(String userId);

    /**
     * Ops decided before this result came in - what to remember now so the result can override that decision.
     * Null when there's nothing to override.
     */
    default OpsOverride startOverride(String userId) {
        return null;
    }

    /** The result has been applied over ops's decision - record it and put everything in step. */
    default void finishOverride(String userId, SmileIdDecision decision, OpsOverride override) {
    }

    /** The driver's overall status, and whether they had ever been verified, before the result landed. */
    record OpsOverride(org.coryd.common.enums.VerificationStatus overallBefore, boolean verifiedBefore) {
    }

    /** Smile ID said clear - approve, or send to ops if what it read doesn't match the person. */
    SmileIdDecision onClear(String userId, SmileIdResult.IdFields idFields);

    /** A reason only this check knows how to handle, or null for the shared handling. */
    SmileIdDecision onReason(String status, String reason, String message);

    /** idFields: the record Smile ID checked against (NIMC's for a NIN), null if it sent none. */
    void approve(String userId, SmileIdResult.IdFields idFields);

    void sendToOps(String userId);

    /** Anything this check adds to a result's note for ops - "" when there's nothing. */
    default String resultNote(String userId) {
        return "";
    }

    /** Anything this check does once a result is recorded, whatever it was. */
    default void afterResult(String userId, SmileIdDecision decision) {
    }
}
