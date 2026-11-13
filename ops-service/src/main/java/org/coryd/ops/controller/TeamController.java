package org.coryd.ops.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.coryd.common.enums.OpsPermission;
import org.coryd.common.enums.OpsRole;
import org.coryd.common.exception.BadRequestException;
import org.coryd.common.response.ApiResponse;
import org.coryd.common.security.CoRydUserDetails;
import org.coryd.ops.client.OpsTeamClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin team (SUPER_ADMIN only - OpsAccessPolicy; identity-service checks the acting admin again). See
 * docs/ops-console/admin-team.md.
 */
@RestController
@RequestMapping("/team")
@RequiredArgsConstructor
public class TeamController {

    private final OpsTeamClient opsTeamClient;

    public record InviteBody(@NotBlank(message = "Enter their email address") @Email(message = "Enter a valid email address") @Size(max = 254) String email,
                             @NotBlank(message = "Enter their first name") @Size(max = 100) String firstName,
                             @NotBlank(message = "Enter their last name") @Size(max = 100) String lastName,
                             @NotNull(message = "Choose their ops role") OpsRole opsRole) {
    }

    public record RoleBody(@NotNull(message = "Choose the new ops role") OpsRole opsRole, @Size(max = 500) String reason) {
    }

    public record ReasonBody(@Size(max = 500) String reason) {
    }

    public record RoleView(OpsRole role, String label, List<OpsPermission> permissions) {
    }

    /** The roles and what each may do - any ops role may read it. */
    @GetMapping("/roles")
    public ApiResponse<List<RoleView>> roles() {
        return ApiResponse.ok(Arrays.stream(OpsRole.values())
                .map(r -> new RoleView(r, r.label(), r.permissions().stream().sorted().toList())).toList());
    }

    /** Everyone with console access or an invite, active first. you: the row is the signed-in admin. */
    @GetMapping
    public ApiResponse<JsonNode> list(@AuthenticationPrincipal CoRydUserDetails admin) {
        JsonNode list = opsTeamClient.list();
        if (list != null) {
            list.forEach(row -> markYou(row, admin));
        }
        return ApiResponse.ok(list);
    }

    @GetMapping("/{adminId}")
    public ApiResponse<JsonNode> get(@AuthenticationPrincipal CoRydUserDetails admin, @PathVariable String adminId) {
        return ApiResponse.ok(markYou(opsTeamClient.get(adminId), admin));
    }

    @PostMapping("/invites")
    public ApiResponse<JsonNode> invite(@AuthenticationPrincipal CoRydUserDetails admin, @Valid @RequestBody InviteBody body) {
        Map<String, Object> request = actor(admin);
        request.put("email", body.email().trim());
        request.put("firstName", body.firstName().trim());
        request.put("lastName", body.lastName().trim());
        request.put("opsRole", body.opsRole());
        return ApiResponse.ok("Invite sent", opsTeamClient.invite(request));
    }

    @PostMapping("/{adminId}/invite/resend")
    public ApiResponse<JsonNode> resend(@AuthenticationPrincipal CoRydUserDetails admin, @PathVariable String adminId,
                                        @Valid @RequestBody(required = false) ReasonBody body) {
        return ApiResponse.ok("Invite sent again", opsTeamClient.act(adminId, "invite/resend", withReason(admin, body)));
    }

    @PostMapping("/{adminId}/invite/revoke")
    public ApiResponse<JsonNode> revoke(@AuthenticationPrincipal CoRydUserDetails admin, @PathVariable String adminId,
                                        @Valid @RequestBody(required = false) ReasonBody body) {
        return ApiResponse.ok("Invite revoked", opsTeamClient.act(adminId, "invite/revoke", withReason(admin, body)));
    }

    @PostMapping("/{adminId}/role")
    public ApiResponse<JsonNode> role(@AuthenticationPrincipal CoRydUserDetails admin, @PathVariable String adminId,
                                      @Valid @RequestBody RoleBody body) {
        if (admin.getUserId().equals(adminId)) {
            throw new BadRequestException("You can't change your own ops role - ask another super admin");
        }
        Map<String, Object> request = withReason(admin, new ReasonBody(body.reason()));
        request.put("opsRole", body.opsRole());
        return ApiResponse.ok("Ops role changed", opsTeamClient.act(adminId, "role", request));
    }

    @PostMapping("/{adminId}/deactivate")
    public ApiResponse<JsonNode> deactivate(@AuthenticationPrincipal CoRydUserDetails admin, @PathVariable String adminId,
                                            @Valid @RequestBody(required = false) ReasonBody body) {
        if (admin.getUserId().equals(adminId)) {
            throw new BadRequestException("You can't deactivate yourself - ask another super admin");
        }
        requireReason(body, "Give a reason for deactivating this admin");
        return ApiResponse.ok("Admin deactivated - every session they had has ended",
                opsTeamClient.act(adminId, "deactivate", withReason(admin, body)));
    }

    @PostMapping("/{adminId}/reactivate")
    public ApiResponse<JsonNode> reactivate(@AuthenticationPrincipal CoRydUserDetails admin, @PathVariable String adminId,
                                            @Valid @RequestBody(required = false) ReasonBody body) {
        requireReason(body, "Give a reason for reactivating this admin");
        return ApiResponse.ok("Admin reactivated", opsTeamClient.act(adminId, "reactivate", withReason(admin, body)));
    }

    /** Clears the admin's two-factor sign-in and ends every session they have; they set it up again at next sign-in. */
    @PostMapping("/{adminId}/mfa/reset")
    public ApiResponse<JsonNode> resetMfa(@AuthenticationPrincipal CoRydUserDetails admin, @PathVariable String adminId,
                                          @Valid @RequestBody(required = false) ReasonBody body) {
        if (admin.getUserId().equals(adminId)) {
            throw new BadRequestException("You can't reset your own two-factor sign-in - ask another super admin");
        }
        requireReason(body, "Give a reason for resetting two-factor sign-in");
        return ApiResponse.ok("Two-factor sign-in reset - every session they had has ended",
                markYou(opsTeamClient.mfa(adminId, "reset", withReason(admin, body)), admin));
    }

    private static void requireReason(ReasonBody body, String message) {
        if (body == null || body.reason() == null || body.reason().isBlank()) {
            throw new BadRequestException(message);
        }
    }

    private static Map<String, Object> actor(CoRydUserDetails admin) {
        Map<String, Object> request = new HashMap<>();
        request.put("opsUserId", admin.getUserId());
        request.put("opsUserName", admin.getFullName());
        return request;
    }

    private static Map<String, Object> withReason(CoRydUserDetails admin, ReasonBody body) {
        Map<String, Object> request = actor(admin);
        request.put("reason", body == null || body.reason() == null ? null : body.reason().trim());
        return request;
    }

    private static JsonNode markYou(JsonNode row, CoRydUserDetails admin) {
        if (row instanceof ObjectNode object) {
            object.put("you", admin.getUserId().equals(row.path("userId").asText()));
        }
        return row;
    }
}
