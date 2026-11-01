package org.coryd.ops.controller;

import lombok.RequiredArgsConstructor;
import org.coryd.common.enums.DocumentExpirySort;
import org.coryd.common.enums.DocumentExpiryTab;
import org.coryd.common.response.ApiResponse;
import org.coryd.common.response.PagedResponse;
import org.coryd.common.security.CoRydUserDetails;
import org.coryd.ops.dto.DocumentExpiryCounts;
import org.coryd.ops.dto.DocumentExpiryItem;
import org.coryd.ops.dto.DocumentExpiryNotifyResult;
import org.coryd.ops.service.DocumentExpiryOpsService;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Document Expiry Tracker - insurance and roadworthiness against their expiry dates, and reminding drivers. */
@RestController
@RequestMapping("/document-expiry")
@RequiredArgsConstructor
public class DocumentExpiryController {

    private final DocumentExpiryOpsService documentExpiryOpsService;

    /** tab is omitted for "All". Most urgent first unless sorted. */
    @GetMapping
    public ApiResponse<PagedResponse<DocumentExpiryItem>> documents(@RequestParam(required = false) DocumentExpiryTab tab,
                                                                    @RequestParam(required = false) String search,
                                                                    @RequestParam(required = false) DocumentExpirySort sort,
                                                                    @RequestParam(required = false) Sort.Direction direction,
                                                                    @RequestParam(defaultValue = "0") int page,
                                                                    @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(documentExpiryOpsService.documents(tab, search, sort, direction, page, size));
    }

    /** The cards and tab badges - they follow the same search as the list. */
    @GetMapping("/counts")
    public ApiResponse<DocumentExpiryCounts> counts(@RequestParam(required = false) String search) {
        return ApiResponse.ok(documentExpiryOpsService.counts(search));
    }

    /** A row's Notify: pushes the driver a reminder. Returns the row as it now is. */
    @PostMapping("/{documentId}/notify")
    public ApiResponse<DocumentExpiryItem> notifyDriver(@PathVariable String documentId, @AuthenticationPrincipal CoRydUserDetails admin) {
        return ApiResponse.ok(documentExpiryOpsService.notifyDriver(documentId, admin));
    }

    /** Notify all: every expiring or expired document the tab and search show, except those reminded within the cool-down. */
    @PostMapping("/notify-all")
    public ApiResponse<DocumentExpiryNotifyResult> notifyAll(@RequestParam(required = false) DocumentExpiryTab tab,
                                                             @RequestParam(required = false) String search,
                                                             @AuthenticationPrincipal CoRydUserDetails admin) {
        return ApiResponse.ok(documentExpiryOpsService.notifyAll(tab, search, admin));
    }
}
