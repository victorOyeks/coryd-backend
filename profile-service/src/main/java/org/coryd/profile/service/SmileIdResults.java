package org.coryd.profile.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.coryd.profile.dto.SmileIdResult;

import java.util.List;
import java.util.Locale;

/** What the identity and licence checks both need from a Smile ID result. */
final class SmileIdResults {

    private SmileIdResults() {
    }

    /** Smile Secure: the same NIN, document or face already seen on another CoRyd account. Ops sees it; it decides nothing by itself. */
    static String fraudNote(SmileIdResult result) {
        List<SmileIdResult.SuspectUser> suspects = result.antifraud() == null || result.antifraud().smileSecure() == null
                ? null : result.antifraud().smileSecure().suspectUsers();
        if (suspects == null || suspects.isEmpty()) {
            return "";
        }
        StringBuilder note = new StringBuilder(" | Same ID or face as another account:");
        suspects.forEach(s -> note.append(' ').append(s.userId()).append(' ').append(s.reasons()));
        return note.toString();
    }

    /** The result as kept on verification_checks.raw_response - no image or receipt links. */
    static String audit(ObjectMapper objectMapper, SmileIdResult result) {
        try {
            return objectMapper.writeValueAsString(result.forAudit());
        } catch (Exception e) {
            return null;
        }
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    static String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    static String safe(String value) {
        return value == null ? "" : value;
    }

    static String truncate(String value) {
        return value.length() <= 500 ? value : value.substring(0, 497) + "...";
    }

    static String tag(boolean sandbox) {
        return sandbox ? "[SANDBOX] " : "";
    }
}
