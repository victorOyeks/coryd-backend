package org.coryd.ops.controller;

import lombok.RequiredArgsConstructor;
import org.coryd.common.audit.OpsAuditCategory;
import org.coryd.common.response.ApiResponse;
import org.coryd.ops.audit.AuditFeed;
import org.coryd.ops.audit.AuditFilter;
import org.coryd.ops.audit.OpsAuditService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

/** The unified audit log (SUPER_ADMIN - OpsAccessPolicy). See docs/ops-console/audit-log.md. */
@RestController
@RequestMapping("/audit")
@RequiredArgsConstructor
public class AuditController {

    private final OpsAuditService auditService;

    @GetMapping
    public ApiResponse<AuditFeed> audit(@RequestParam(required = false) String actorId,
                                        @RequestParam(required = false) List<String> action,
                                        @RequestParam(required = false) List<OpsAuditCategory> category,
                                        @RequestParam(required = false) List<String> targetType,
                                        @RequestParam(required = false) String targetId,
                                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                        @RequestParam(required = false) String search,
                                        @RequestParam(required = false) String cursor,
                                        @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(auditService.page(new AuditFilter(actorId, action, category, targetType, targetId, from, to, search),
                cursor, size));
    }

    /** The filtered feed as CSV, newest first, up to `limit` rows (default and maximum 10,000). X-Audit-Truncated: true when cut off. */
    @GetMapping(value = "/export", produces = "text/csv")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) String actorId,
                                         @RequestParam(required = false) List<String> action,
                                         @RequestParam(required = false) List<OpsAuditCategory> category,
                                         @RequestParam(required = false) List<String> targetType,
                                         @RequestParam(required = false) String targetId,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                         @RequestParam(required = false) String search,
                                         @RequestParam(required = false) Integer limit) {
        OpsAuditService.Export export = auditService.export(
                new AuditFilter(actorId, action, category, targetType, targetId, from, to, search), limit);
        byte[] body = ("﻿" + OpsAuditService.csv(export.rows())).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + OpsAuditService.fileName() + "\"")
                .header("X-Audit-Rows", String.valueOf(export.rows().size()))
                .header("X-Audit-Truncated", String.valueOf(export.truncated()))
                .header(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, "Content-Disposition, X-Audit-Rows, X-Audit-Truncated")
                .body(body);
    }
}
