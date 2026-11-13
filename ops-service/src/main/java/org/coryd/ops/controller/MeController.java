package org.coryd.ops.controller;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.enums.OpsPermission;
import org.coryd.common.enums.OpsRole;
import org.coryd.common.response.ApiResponse;
import org.coryd.common.security.CoRydUserDetails;
import org.coryd.ops.access.OpsAccess;
import org.coryd.ops.client.OpsTeamClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Who is signed in to the console and what their ops role lets them do - the console hides what they can't use. Also the
 * admin's own two-factor sign-in (/me/mfa/**, every ops role; identity-service keeps it). See docs/ops-console/security.md.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class MeController {

    private final OpsTeamClient opsTeamClient;

    @Value("${ops.session.idle-minutes:30}")
    private long defaultIdleMinutes;

    @Value("${ops.session.absolute-hours:12}")
    private long defaultAbsoluteHours;

    /**
     * opsRole and permissions are what this session's token grants (what every endpoint enforces). email, mfaEnabled and
     * mfaRequired come from identity-service (null if it can't be reached). sessionExpiresAt: when this session ends however
     * active it is (from the token); sessionIdleMinutes: how long unused before it ends.
     */
    public record Me(String userId, String firstName, String lastName, String name, String email, OpsRole opsRole,
                     String opsRoleLabel, List<OpsPermission> permissions, Boolean mfaEnabled, Boolean mfaRequired,
                     long sessionIdleMinutes, long sessionAbsoluteHours, Instant sessionExpiresAt) {
    }

    public record CodeBody(@NotBlank(message = "Enter the 6-digit code from your app") @Size(max = 20) String code) {
    }

    public record DisableBody(@NotBlank(message = "Enter the 6-digit code from your app") @Size(max = 20) String code,
                              @Size(max = 500) String reason) {
    }

    @GetMapping("/me")
    public ApiResponse<Me> me(@AuthenticationPrincipal CoRydUserDetails admin) {
        OpsRole role = OpsAccess.role(admin);
        String email = null;
        Boolean mfaEnabled = null;
        Boolean mfaRequired = null;
        long idle = defaultIdleMinutes;
        long absolute = defaultAbsoluteHours;
        try {
            JsonNode account = opsTeamClient.get(admin.getUserId());
            email = account == null || account.path("email").isNull() ? null : account.path("email").asText(null);
            opsTeamClient.seen(admin.getUserId());
            JsonNode mfa = opsTeamClient.mfaStatus(admin.getUserId());
            if (mfa != null && mfa.has("mfaEnabled")) {
                mfaEnabled = mfa.path("mfaEnabled").asBoolean();
                mfaRequired = mfa.path("mfaRequired").asBoolean();
                idle = mfa.path("sessionIdleMinutes").asLong(idle);
                absolute = mfa.path("sessionAbsoluteHours").asLong(absolute);
            }
        } catch (RuntimeException e) {
            log.debug("Admin details for {} unavailable: {}", admin.getUserId(), e.getMessage());
        }
        List<OpsPermission> permissions = role == null ? List.of() : List.copyOf(role.permissions());
        return ApiResponse.ok(new Me(admin.getUserId(), admin.getFirstName(), admin.getLastName(), admin.getFullName(), email, role,
                role == null ? null : role.label(), permissions.stream().sorted().toList(), mfaEnabled, mfaRequired, idle, absolute,
                admin.getSessionExpiresAt()));
    }

    /** {mfaEnabled, mfaRequired, enabledAt, recoveryCodesLeft, setupPending, accessTokenMinutes, sessionIdleMinutes, sessionAbsoluteHours}. */
    @GetMapping("/me/mfa")
    public ApiResponse<JsonNode> mfa(@AuthenticationPrincipal CoRydUserDetails admin) {
        return ApiResponse.ok(opsTeamClient.mfaStatus(admin.getUserId()));
    }

    /** A new secret for the app - {secret, otpauthUrl, issuer, account}; nothing changes until /me/mfa/enable. */
    @PostMapping("/me/mfa/setup")
    public ApiResponse<JsonNode> setup(@AuthenticationPrincipal CoRydUserDetails admin) {
        return ApiResponse.ok(opsTeamClient.mfa(admin.getUserId(), "setup", null));
    }

    /** The first code from the app turns two-factor sign-in on - {recoveryCodes} (10, shown once). */
    @PostMapping("/me/mfa/enable")
    public ApiResponse<JsonNode> enable(@AuthenticationPrincipal CoRydUserDetails admin, @Valid @RequestBody CodeBody body) {
        return ApiResponse.ok("Two-factor sign-in is on", opsTeamClient.mfa(admin.getUserId(), "enable", Map.of("code", body.code())));
    }

    /** A current code (or a recovery code) and a reason. Refused while two-factor sign-in is required. */
    @PostMapping("/me/mfa/disable")
    public ApiResponse<JsonNode> disable(@AuthenticationPrincipal CoRydUserDetails admin, @Valid @RequestBody DisableBody body) {
        Map<String, Object> request = new HashMap<>();
        request.put("code", body.code());
        request.put("reason", body.reason() == null ? null : body.reason().trim());
        return ApiResponse.ok("Two-factor sign-in is off", opsTeamClient.mfa(admin.getUserId(), "disable", request));
    }

    /** A current code from the app; ten new recovery codes, the old ones stop working. */
    @PostMapping("/me/mfa/recovery-codes")
    public ApiResponse<JsonNode> recoveryCodes(@AuthenticationPrincipal CoRydUserDetails admin, @Valid @RequestBody CodeBody body) {
        return ApiResponse.ok("New recovery codes - the old ones no longer work",
                opsTeamClient.mfa(admin.getUserId(), "recovery-codes", Map.of("code", body.code())));
    }
}
