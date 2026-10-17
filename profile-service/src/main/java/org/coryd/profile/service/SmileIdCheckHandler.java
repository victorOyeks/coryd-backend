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

    /** False once ops has it or has decided - a late result never overrides that. */
    boolean awaitingResult(String userId);

    /** Smile ID said clear - approve, or send to ops if what it read doesn't match the person. */
    SmileIdDecision onClear(String userId, SmileIdResult.IdFields idFields);

    /** A reason only this check knows how to handle, or null for the shared handling. */
    SmileIdDecision onReason(String status, String reason, String message);

    void approve(String userId);

    void sendToOps(String userId);
}
