package org.coryd.profile.controller.internal;

import lombok.RequiredArgsConstructor;
import org.coryd.common.enums.DocumentExpirySort;
import org.coryd.common.enums.DocumentExpiryTab;
import org.coryd.common.response.ApiResponse;
import org.coryd.common.response.PagedResponse;
import org.coryd.profile.dto.DocumentExpiryCounts;
import org.coryd.profile.dto.DocumentExpiryNotifyResult;
import org.coryd.profile.dto.DocumentExpiryView;
import org.coryd.profile.service.DocumentExpiryService;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** ops-service's Document Expiry Tracker - service-to-service only, behind InternalApiKeyFilter. by is the signed-in admin. */
@RestController
@RequestMapping("/internal/document-expiry")
@RequiredArgsConstructor
public class InternalDocumentExpiryController {

    private final DocumentExpiryService documentExpiryService;

    @GetMapping
    public ApiResponse<PagedResponse<DocumentExpiryView>> tracker(@RequestParam(required = false) DocumentExpiryTab tab,
                                                                 @RequestParam(required = false) String search,
                                                                 @RequestParam(required = false) DocumentExpirySort sort,
                                                                 @RequestParam(required = false) Sort.Direction direction,
                                                                 @RequestParam(defaultValue = "0") int page,
                                                                 @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(documentExpiryService.tracker(tab, search, sort, direction, page, size));
    }

    @GetMapping("/counts")
    public ApiResponse<DocumentExpiryCounts> counts(@RequestParam(required = false) String search) {
        return ApiResponse.ok(documentExpiryService.counts(search));
    }

    @PostMapping("/{documentId}/notify")
    public ApiResponse<DocumentExpiryView> notifyDriver(@PathVariable String documentId, @RequestParam String by) {
        return ApiResponse.ok(documentExpiryService.notifyDriver(documentId, by));
    }

    @PostMapping("/notify-all")
    public ApiResponse<DocumentExpiryNotifyResult> notifyAll(@RequestParam(required = false) DocumentExpiryTab tab,
                                                             @RequestParam(required = false) String search,
                                                             @RequestParam String by) {
        return ApiResponse.ok(documentExpiryService.notifyAll(tab, search, by));
    }
}
