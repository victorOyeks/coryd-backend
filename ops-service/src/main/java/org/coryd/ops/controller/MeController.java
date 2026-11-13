package org.coryd.ops.controller;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.enums.OpsPermission;
import org.coryd.common.enums.OpsRole;
import org.coryd.common.response.ApiResponse;
import org.coryd.common.security.CoRydUserDetails;
import org.coryd.ops.access.OpsAccess;
import org.coryd.ops.client.OpsTeamClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Who is signed in to the console and what their ops role lets them do - the console hides what they can't use. */
@Slf4j
@RestController
@RequiredArgsConstructor
public class MeController {

    private final OpsTeamClient opsTeamClient;

    /**
     * opsRole and permissions are what this session's token grants (what every endpoint enforces). email comes from
     * identity-service; null if it can't be reached. Also moves the admin's "last seen" on.
     */
    public record Me(String userId, String firstName, String lastName, String name, String email, OpsRole opsRole,
                     String opsRoleLabel, List<OpsPermission> permissions) {
    }

    @GetMapping("/me")
    public ApiResponse<Me> me(@AuthenticationPrincipal CoRydUserDetails admin) {
        OpsRole role = OpsAccess.role(admin);
        String email = null;
        try {
            JsonNode account = opsTeamClient.get(admin.getUserId());
            email = account == null || account.path("email").isNull() ? null : account.path("email").asText(null);
            opsTeamClient.seen(admin.getUserId());
        } catch (RuntimeException e) {
            log.debug("Admin details for {} unavailable: {}", admin.getUserId(), e.getMessage());
        }
        List<OpsPermission> permissions = role == null ? List.of() : List.copyOf(role.permissions());
        return ApiResponse.ok(new Me(admin.getUserId(), admin.getFirstName(), admin.getLastName(), admin.getFullName(), email, role,
                role == null ? null : role.label(), permissions.stream().sorted().toList()));
    }
}
