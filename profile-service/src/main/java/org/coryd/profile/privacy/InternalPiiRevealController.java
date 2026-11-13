package org.coryd.profile.privacy;

import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.coryd.common.enums.PiiField;
import org.coryd.common.response.ApiResponse;
import org.coryd.common.security.CoRydUserDetails;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * ops-service only (internal key + the admin's own token): one audited reveal of a person's full NIN, licence number or
 * date of birth. See PiiRevealService and docs/ops-console/privacy.md.
 */
@Hidden
@RestController
@RequiredArgsConstructor
public class InternalPiiRevealController {

    private final PiiRevealService revealService;

    public record RevealRequest(PiiField field, String reason) {
    }

    @PostMapping("/internal/ops/users/{userId}/reveal")
    public ApiResponse<PiiRevealService.Revealed> reveal(@AuthenticationPrincipal CoRydUserDetails principal, @PathVariable String userId,
                                                         @RequestBody(required = false) RevealRequest body) {
        return ApiResponse.ok("Revealed - this view is recorded", revealService.reveal(userId, body == null ? null : body.field(),
                body == null ? null : body.reason(), principal));
    }
}
