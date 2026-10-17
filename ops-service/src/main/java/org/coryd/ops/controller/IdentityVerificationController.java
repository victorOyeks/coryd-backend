package org.coryd.ops.controller;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.coryd.ops.client.ProfileServiceClient;
import org.coryd.ops.dto.IdentityVerificationDecision;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Identity (NIN + live selfie) checks - riders and drivers - Smile ID couldn't settle on its own: the NIN's
 * last four digits and the check's note (the selfie is in the Smile ID portal under the job it names), then
 * approve or reject with a reason. Passed through as profile-service shapes it - this console adds nothing.
 */
@RestController
@RequestMapping("/identity-verification")
@RequiredArgsConstructor
public class IdentityVerificationController {

    private final ProfileServiceClient profileServiceClient;

    @GetMapping("/queue")
    public ApiResponse<JsonNode> queue(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(profileServiceClient.identityVerificationQueue(page, Math.min(size, 50)));
    }

    @PostMapping("/{userId}/decision")
    public ApiResponse<JsonNode> decide(@PathVariable String userId, @Valid @RequestBody IdentityVerificationDecision decision) {
        return ApiResponse.ok(profileServiceClient.decideIdentityVerification(userId, decision.approve(), decision.reason()));
    }
}
