package org.coryd.ops.controller;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.coryd.common.enums.AccountDeletionStatus;
import org.coryd.common.response.ApiResponse;
import org.coryd.ops.service.AccountOpsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Account deletion requests - read-only. PENDING: the last try was refused (the reasons are listed); COMPLETED: deleted. */
@RestController
@RequestMapping("/account-deletions")
@RequiredArgsConstructor
public class AccountDeletionController {

    private final AccountOpsService accountOpsService;

    @GetMapping
    public ApiResponse<JsonNode> list(@RequestParam(required = false) AccountDeletionStatus status,
                                      @RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(accountOpsService.deletionRequests(status, page, size));
    }

    @GetMapping("/{requestId}")
    public ApiResponse<JsonNode> get(@PathVariable String requestId) {
        return ApiResponse.ok(accountOpsService.deletionRequest(requestId));
    }
}
