package org.coryd.ops.privacy;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.coryd.common.enums.PiiField;
import org.coryd.common.exception.BadRequestException;
import org.coryd.common.response.ApiResponse;
import org.coryd.common.security.CoRydUserDetails;
import org.coryd.ops.client.ProfileServiceClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;

/**
 * POST /ops/users/{userId}/reveal (docs/ops-console/privacy.md): the full NIN, licence number or date of birth the console
 * otherwise shows masked. A reason is required (it goes in the audit log as PII_REVEALED, kept by profile-service), the
 * admin needs VERIFICATION_DECIDE or ACCOUNT_MANAGE (OpsAccessPolicy), and reveals are rate limited per admin.
 * Returns {field, value, source, revealedAt, expiresInSeconds: 60} - the console stops showing the value after that.
 */
@RestController
@RequiredArgsConstructor
public class PrivacyController {

    public static final String NEED_A_REASON = "Say why you need to see it - it goes in the audit log";

    private final ProfileServiceClient profileServiceClient;
    private final RevealRateLimiter rateLimiter;

    public record RevealBody(String field, String reason) {
    }

    @PostMapping("/users/{userId}/reveal")
    public ApiResponse<JsonNode> reveal(@AuthenticationPrincipal CoRydUserDetails admin, @PathVariable String userId,
                                        @RequestBody(required = false) RevealBody body) {
        PiiField field = field(body == null ? null : body.field());
        String reason = body == null || body.reason() == null ? "" : body.reason().trim();
        if (reason.isEmpty()) {
            throw new BadRequestException(NEED_A_REASON);
        }
        if (reason.length() > 500) {
            throw new BadRequestException("Keep the reason under 500 characters");
        }
        String adminId = admin == null ? null : admin.getUserId();
        rateLimiter.acquire(adminId);
        try {
            return ApiResponse.ok("Revealed - this view is recorded", profileServiceClient.revealPii(userId, field, reason));
        } catch (RuntimeException e) {
            rateLimiter.release(adminId);
            throw e;
        }
    }

    static PiiField field(String value) {
        if (value != null) {
            for (PiiField f : PiiField.values()) {
                if (f.name().equalsIgnoreCase(value.trim())) {
                    return f;
                }
            }
        }
        throw new BadRequestException("field must be one of " + Arrays.toString(PiiField.values()));
    }
}
