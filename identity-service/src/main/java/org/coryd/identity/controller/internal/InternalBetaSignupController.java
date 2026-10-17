package org.coryd.identity.controller.internal;

import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.coryd.common.response.PagedResponse;
import org.coryd.identity.entity.BetaSignup;
import org.coryd.identity.repository.BetaSignupRepository;
import org.coryd.identity.service.BetaSignupService;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Service-to-service only (ops-service, for the Ops Console) - gated by InternalApiKeyFilter via the /internal/** prefix. */
@Hidden
@RestController
@RequestMapping("/internal/beta-signups")
@RequiredArgsConstructor
public class InternalBetaSignupController {

    private final BetaSignupService betaSignupService;

    @GetMapping
    public ApiResponse<PagedResponse<BetaSignup>> list(@RequestParam(required = false) BetaSignup.Status status,
                                                       @RequestParam(required = false) BetaSignup.Intent willMostly,
                                                       @RequestParam(required = false) BetaSignup.Device device,
                                                       @RequestParam(required = false) String search,
                                                       @RequestParam(defaultValue = "0") int page,
                                                       @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(betaSignupService.list(status, willMostly, device, search, PageRequest.of(page, Math.min(size, 100))));
    }

    @GetMapping("/summary")
    public ApiResponse<BetaSignupRepository.Summary> summary() {
        return ApiResponse.ok(betaSignupService.summary());
    }

    @PostMapping("/{id}/invited")
    public ApiResponse<BetaSignup> invited(@PathVariable String id, @RequestParam(required = false) String opsUserId) {
        return ApiResponse.ok(betaSignupService.markInvited(id, opsUserId));
    }
}
