package org.coryd.profile.controller.internal;

import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.coryd.common.audit.OpsAuditPage;
import org.coryd.common.audit.OpsAuditQuery;
import org.coryd.common.response.ApiResponse;
import org.coryd.profile.audit.ProfileAuditSource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** ops-service's unified audit log reads this service's ops actions here (filter shape: OpsAuditQuery). /internal/** only. */
@Hidden
@RestController
@RequestMapping("/internal/ops/audit")
@RequiredArgsConstructor
public class InternalOpsAuditController {

    private final ProfileAuditSource source;

    @GetMapping
    public ApiResponse<OpsAuditPage> audit(@ModelAttribute OpsAuditQuery query) {
        return ApiResponse.ok(source.page(query));
    }
}
