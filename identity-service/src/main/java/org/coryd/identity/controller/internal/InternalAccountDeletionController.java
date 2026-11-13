package org.coryd.identity.controller.internal;

import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.coryd.common.enums.AccountDeletionStatus;
import org.coryd.common.response.ApiResponse;
import org.coryd.common.response.PagedResponse;
import org.coryd.identity.dto.AccountDeletionView;
import org.coryd.identity.service.AccountDeletionRecorder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The ops console's account deletion requests (read-only), through ops-service. */
@Hidden
@RestController
@RequestMapping("/internal/account-deletions")
@RequiredArgsConstructor
public class InternalAccountDeletionController {

    private final AccountDeletionRecorder deletionRecorder;

    /** status omitted: both. Most recent try first. */
    @GetMapping
    public ApiResponse<PagedResponse<AccountDeletionView>> list(@RequestParam(required = false) AccountDeletionStatus status,
                                                                @RequestParam(defaultValue = "0") int page,
                                                                @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(deletionRecorder.list(status, page, size));
    }

    @GetMapping("/{id}")
    public ApiResponse<AccountDeletionView> get(@PathVariable String id) {
        return ApiResponse.ok(deletionRecorder.get(id));
    }
}
