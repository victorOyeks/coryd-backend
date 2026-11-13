package org.coryd.identity.controller.internal;

import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.coryd.common.audit.OpsAuditPage;
import org.coryd.common.audit.OpsAuditQuery;
import org.coryd.common.response.ApiResponse;
import org.coryd.identity.audit.IdentityAuditSource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** ops-service's unified audit log reads account and admin-team actions here (filter shape: OpsAuditQuery). */
@Hidden
@RestController
@RequestMapping("/internal/ops/audit")
@RequiredArgsConstructor
public class InternalOpsAuditController {

    private final IdentityAuditSource source;

    @GetMapping
    public ApiResponse<OpsAuditPage> audit(@ModelAttribute OpsAuditQuery query) {
        return ApiResponse.ok(source.page(query));
    }
}
